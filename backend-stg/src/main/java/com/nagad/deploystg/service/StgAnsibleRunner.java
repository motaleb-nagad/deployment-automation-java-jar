package com.nagad.deploystg.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the console stream for a staging run. In demo mode ({@code nagad.ansible.simulate})
 * it builds scripted Ansible output; in production it SSHes into the jump host and invokes the
 * {@code stg-deployment} bundle's {@code ./run.sh} / {@code portalui/run.sh}. It also stages
 * manually-uploaded files into the bundle before a run. Fully self-contained.
 */
@Component
public class StgAnsibleRunner {

    /** level: user | dim | ink | task | ok | ch | fatal. rail fields drive the per-host tracker. */
    public record Line(String level, String text, String railHost, String railAction, String railState) {
        static Line log(String level, String text) { return new Line(level, text, null, null, null); }
        static Line host(String level, String text, String host, String action, String state) {
            return new Line(level, text, host, action, state);
        }
    }

    /** Bundle-relative directories manually-uploaded files land in (per the stg-deployment layout). */
    public static final String JARS_DIR = "roles/deployment/files/jars";
    public static final String CFG_DIR = "roles/deployment/files/cfg";
    public static final String PORTALUI_DIR = "portalui/roles/portalui/files";

    private final String stgDir;
    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String sshKeyPath;
    private final boolean strictHostKey;

    private volatile Path readyKey;

    public StgAnsibleRunner(
            @Value("${nagad.ansible.stg.working-dir}") String stgDir,
            @Value("${nagad.ansible.ssh.host:host.docker.internal}") String sshHost,
            @Value("${nagad.ansible.ssh.port:40167}") int sshPort,
            @Value("${nagad.ansible.ssh.user:konasl}") String sshUser,
            @Value("${nagad.ansible.ssh.key:/run/secrets/deploy_key}") String sshKeyPath,
            @Value("${nagad.ansible.ssh.strict-host-key-checking:false}") boolean strictHostKey) {
        this.stgDir = stgDir;
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.sshUser = sshUser;
        this.sshKeyPath = sshKeyPath;
        this.strictHostKey = strictHostKey;
    }

    public String stgDir() { return stgDir; }

    // ---- commands ---------------------------------------------------------------------------

    /** The staging jar/config wrapper command: {@code ./run.sh <group> all <apps> <actions>}. */
    public String command(String group, List<String> apps, List<String> actions) {
        return "./run.sh " + group + " all " + String.join(",", apps) + " " + String.join(",", actions);
    }

    /** The staging portal-UI wrapper command:
     *  {@code portalui/run.sh <uis> [date] [--url-fix] [--size-fix]}. */
    public String portalUiCommand(List<String> uis, String date, boolean urlFix, boolean sizeFix) {
        StringBuilder sb = new StringBuilder("./run.sh ").append(String.join(",", uis));
        if (date != null && !date.isBlank()) sb.append(' ').append(date.trim());
        if (urlFix) sb.append(" --url-fix");
        if (sizeFix) sb.append(" --size-fix");
        return sb.toString();
    }

    /** Rail phases shown per host for a staging portal-UI deploy. */
    public static List<String> portalUiPhases() {
        return List.of("copy", "backup", "deploy");
    }

    // ---- upload -----------------------------------------------------------------------------

    /**
     * Copy an uploaded file to the staging bundle on the jump host. In real mode the bytes are
     * piped over SSH into {@code <stgDir>/<relDir>/<filename>} (the dir is created if missing);
     * in demo mode they are written to a local staging area so the flow is testable offline.
     * Returns the absolute path the file now lives at.
     */
    public String stageUpload(boolean simulate, String relDir, String filename, byte[] bytes)
            throws IOException, InterruptedException {
        if (simulate) {
            Path base = Path.of(System.getProperty("java.io.tmpdir"), "nagad-stg-uploads", relDir);
            Files.createDirectories(base);
            Path dst = base.resolve(filename);
            Files.write(dst, bytes);
            return dst.toString();
        }
        String dir = stgDir + "/" + relDir;
        String dest = dir + "/" + filename;
        String remote = "mkdir -p " + shq(dir) + " && cat > " + shq(dest);
        ProcessBuilder pb = new ProcessBuilder(sshArgv(remote));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (OutputStream os = proc.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("upload to jump host timed out");
        }
        int code = proc.exitValue();
        if (code != 0) {
            throw new IOException("upload to jump host failed (exit " + code + "): " + out);
        }
        return dest;
    }

    // ---- real execution (production) --------------------------------------------------------

    /** SSH to the jump host and run the staging jar/config wrapper, streaming stdout. */
    public int execute(String group, List<String> apps, List<String> actions, Consumer<Line> sink)
            throws IOException, InterruptedException {
        String cmd = command(group, apps, actions);
        return runRemote("cd " + shq(stgDir) + " && " + cmd, cmd, firstActionOf(actions), sink);
    }

    /** SSH to the jump host and run the staging portal-UI wrapper, streaming stdout. */
    public int executePortalUi(List<String> uis, String date, boolean urlFix, boolean sizeFix, Consumer<Line> sink)
            throws IOException, InterruptedException {
        String cmd = portalUiCommand(uis, date, urlFix, sizeFix);
        return runRemote("cd " + shq(stgDir + "/portalui") + " && " + cmd, cmd, "deploy", sink);
    }

    private int runRemote(String remote, String echo, String initialAction, Consumer<Line> sink)
            throws IOException, InterruptedException {
        sink.accept(Line.log("user", "$ " + echo));
        ProcessBuilder pb = new ProcessBuilder(sshArgv(remote));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String currentAction = initialAction;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                String a = actionFromTask(raw);
                if (a != null) currentAction = a;
                sink.accept(classify(raw, currentAction));
            }
        }
        return proc.waitFor();
    }

    // ---- demo mode (scripted output) --------------------------------------------------------

    /** Demo mode: scripted staging jar/config output (stop/deploy/start per app on the one host). */
    public List<Line> script(String group, String host, List<String> apps, List<String> actions,
                             StgInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("dim", " Env     : STAGING"));
        out.add(Line.log("dim", " Group   : " + group));
        out.add(Line.log("dim", " Limit   : stg-" + group));
        out.add(Line.log("dim", " Apps    : " + String.join(",", apps)));
        out.add(Line.log("dim", " Actions : " + String.join(",", actions)));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("ink", stars("PLAY [stg-" + group + "]")));
        out.add(Line.log("task", stars("TASK [Gathering Facts]")));
        out.add(Line.log("ok", "ok: [" + host + "]"));

        for (String a : actions) {
            for (String app : apps) {
                String jar = inv.jarFor(group, app).orElse(app + "-1.0.jar");
                out.add(Line.log("task", stars("TASK [" + a + " : " + app + "]")));
                long pid = pid("stg" + group + host + app);
                String text = switch (a) {
                    case "stop" -> "changed: [" + host + "] => " + app + " pid " + pid + " stopped";
                    case "deploy" -> "changed: [" + host + "] => " + jar
                            + " -> /home/" + app + "/was/ (backup: " + jar + ".1753257821~)";
                    default -> "changed: [" + host + "] => " + app + " started, pid "
                            + pid("stg" + group + host + app + "n") + " — verified running";
                };
                out.add(Line.host("ch", text, host, a, "done"));
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        int changed = actions.size() * apps.size();
        out.add(Line.log("ok", pad(host) + ": ok=" + (1 + changed) + "  changed=" + changed
                + "  unreachable=0  failed=0"));
        return out;
    }

    /** Demo mode: scripted staging portal-UI output (copy → backup → extract [→ url-fix] per UI). */
    public List<Line> scriptPortalUi(List<String> uis, String date, String host, String cmd,
                                     boolean urlFix, boolean sizeFix) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("dim", " Portal UI deploy (STAGING: " + host + ")"));
        out.add(Line.log("dim", " UI      : " + String.join(",", uis)));
        out.add(Line.log("dim", " Date    : " + ((date == null || date.isBlank()) ? "today" : date.trim())));
        out.add(Line.log("dim", " Mode    : " + (urlFix ? "URL fix" : "as-is") + (sizeFix ? " + MAX_FILE_SIZE" : "")));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("ink", stars("PLAY [PORTAL UI DEPLOYMENT — STAGING]")));
        out.add(Line.log("task", stars("TASK [Pinging Server]")));
        out.add(Line.log("ok", "ok: [" + host + "]"));

        String bkpDate = (date == null || date.isBlank()) ? "today" : date.trim();
        for (String ui : uis) {
            out.add(Line.log("task", stars("TASK [Copying UI files : " + ui + "]")));
            out.add(Line.host("ch", "changed: [" + host + "] => " + ui + ".tar → /tmp", host, "copy", "done"));
            out.add(Line.log("task", stars("TASK [Backing up " + ui + "]")));
            out.add(Line.host("ch", "changed: [" + host + "] => ui/backup/"
                    + ui + "." + bkpDate + ".tar.gz", host, "backup", "done"));
            out.add(Line.log("task", stars("TASK [Extracting " + ui + ".tar]")));
            out.add(Line.host("ch", "changed: [" + host + "] => extracted "
                    + ui + ".tar → /usr/local/nginx/html/ui/" + ui, host, "deploy", "done"));
            if (urlFix) {
                out.add(Line.log("task", stars("TASK [URL fix : " + ui + "]")));
                out.add(Line.host("ch", "changed: [" + host + "] => rewrote backend URLs → "
                        + stgUrlHint(ui), host, "deploy", "done"));
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        int changed = uis.size() * (portalUiPhases().size() + (urlFix ? 1 : 0));
        out.add(Line.log("ok", pad(host) + ": ok=1  changed=" + changed + "  unreachable=0  failed=0"));
        return out;
    }

    /** Representative staging host a UI's backend URLs are rewritten to (demo display only). */
    private static String stgUrlHint(String ui) {
        return switch (ui) {
            case "dms" -> "https://dmstest.mynagad.com";
            case "system" -> "https://systest.mynagad.com";
            case "call-center" -> "https://cctest.mynagad.com";
            default -> "https://<" + ui + ">test.mynagad.com";
        };
    }

    /**
     * Run a command on the jump host over SSH and return its merged stdout/stderr, bounded by a
     * hard timeout. Used by the staging properties flow to stage a pasted block and run the
     * wrapper non-interactively.
     */
    public String capture(String remoteCommand, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(sshArgv(remoteCommand));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("jump-host command timed out after " + timeoutSeconds + "s");
        }
        return out.toString();
    }

    // ---- ssh plumbing -----------------------------------------------------------------------

    private List<String> sshArgv(String remoteCommand) throws IOException {
        List<String> argv = new ArrayList<>(List.of(
                "ssh", "-i", keyFile().toString(),
                "-p", Integer.toString(sshPort),
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "LogLevel=ERROR",
                "-o", "StrictHostKeyChecking=" + (strictHostKey ? "yes" : "no")));
        if (!strictHostKey) { argv.add("-o"); argv.add("UserKnownHostsFile=/dev/null"); }
        argv.add(sshUser + "@" + sshHost);
        argv.add(remoteCommand);
        return argv;
    }

    private synchronized Path keyFile() throws IOException {
        if (readyKey != null) return readyKey;
        Path src = Path.of(sshKeyPath);
        Path dst = Files.createTempFile("stg-deploy-key-", "");
        Files.setPosixFilePermissions(dst, PosixFilePermissions.fromString("rw-------"));
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(dst, PosixFilePermissions.fromString("rw-------"));
        dst.toFile().deleteOnExit();
        readyKey = dst;
        return dst;
    }

    private static final Pattern HOST_LINE =
            Pattern.compile("^(ok|changed|failed|fatal|unreachable|skipping):\\s*\\[([^\\]]+)\\]");

    private static Line classify(String text, String action) {
        Matcher m = HOST_LINE.matcher(text);
        if (m.find()) {
            String verb = m.group(1);
            String host = m.group(2).trim();
            String level = switch (verb) {
                case "changed" -> "ch";
                case "ok", "skipping" -> "ok";
                default -> "fatal";
            };
            String state = (verb.equals("failed") || verb.equals("fatal") || verb.equals("unreachable"))
                    ? "fail" : "done";
            return Line.host(level, text, host, action, state);
        }
        if (text.startsWith("PLAY RECAP") || text.startsWith("PLAY [")) return Line.log("ink", text);
        if (text.startsWith("TASK [")) return Line.log("task", text);
        if (text.startsWith("fatal") || text.startsWith("ERROR") || text.contains("FAILED")) {
            return Line.log("fatal", text);
        }
        if (text.startsWith("====") || text.startsWith(" Env") || text.startsWith(" Group")
                || text.startsWith(" Limit") || text.startsWith(" Apps") || text.startsWith(" Actions")
                || text.startsWith(" Portal") || text.startsWith(" UI") || text.startsWith(" Date")
                || text.startsWith(" Mode")) {
            return Line.log("dim", text);
        }
        return Line.log("ink", text);
    }

    private static String actionFromTask(String line) {
        if (!line.startsWith("TASK [")) return null;
        String l = line.toLowerCase();
        if (l.contains("stop")) return "stop";
        if (l.contains("deploy") || l.contains("replacing") || l.contains("extract")) return "deploy";
        if (l.contains("start")) return "start";
        if (l.contains("copy")) return "copy";
        if (l.contains("back")) return "backup";
        return null;
    }

    private static String firstActionOf(List<String> actions) {
        return actions.isEmpty() ? "" : actions.get(0);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Deterministic pseudo-PID for scripted demo output (FNV-1a, matching the console style). */
    private static long pid(String s) {
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xff);
            h = (h * 16777619L) & 0xffffffffL;
        }
        return 1200 + h % 58000;
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String stars(String t) {
        return t + " " + "*".repeat(Math.max(6, 66 - t.length()));
    }

    private static String pad(String h) {
        return (h + "                    ").substring(0, 20);
    }
}
