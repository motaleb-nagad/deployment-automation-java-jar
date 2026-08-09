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

    // Post-action process verification (read-only) — bounds for waiting on a JVM to come up on
    // start / drain on stop, and the poll interval in between.
    private final boolean verifyEnabled;
    private final int verifyStartTimeout;
    private final int verifyStopTimeout;
    private final int verifyInterval;

    private volatile Path readyKey;

    public StgAnsibleRunner(
            @Value("${nagad.ansible.stg.working-dir}") String stgDir,
            @Value("${nagad.ansible.ssh.host:host.docker.internal}") String sshHost,
            @Value("${nagad.ansible.ssh.port:40167}") int sshPort,
            @Value("${nagad.ansible.ssh.user:konasl}") String sshUser,
            @Value("${nagad.ansible.ssh.key:/run/secrets/deploy_key}") String sshKeyPath,
            @Value("${nagad.ansible.ssh.strict-host-key-checking:false}") boolean strictHostKey,
            @Value("${nagad.verify.enabled:true}") boolean verifyEnabled,
            @Value("${nagad.verify.start-timeout-seconds:180}") int verifyStartTimeout,
            @Value("${nagad.verify.stop-timeout-seconds:45}") int verifyStopTimeout,
            @Value("${nagad.verify.interval-seconds:6}") int verifyInterval) {
        this.stgDir = stgDir;
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.sshUser = sshUser;
        this.sshKeyPath = sshKeyPath;
        this.strictHostKey = strictHostKey;
        this.verifyEnabled = verifyEnabled;
        this.verifyStartTimeout = verifyStartTimeout;
        this.verifyStopTimeout = verifyStopTimeout;
        this.verifyInterval = verifyInterval;
    }

    public String stgDir() { return stgDir; }

    /** Whether post-action process verification is switched on (nagad.verify.enabled). */
    public boolean verifyEnabled() { return verifyEnabled; }

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

    /** Demo mode: scripted staging jar/config output. Each app runs on its own host — one host
     *  for core/portal, per-service hosts for npsb-zone (inv.hostFor). */
    public List<Line> script(String group, String host, List<String> apps, List<String> actions,
                             StgInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        java.util.function.Function<String, String> hostOf = app -> {
            String h = inv.hostFor(group, app);
            return (h == null || h.isBlank()) ? host : h;
        };
        List<String> hosts = apps.stream().map(hostOf).distinct().toList();

        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("dim", " Env     : STAGING"));
        out.add(Line.log("dim", " Group   : " + group));
        out.add(Line.log("dim", " Hosts   : " + String.join(",", hosts)));
        out.add(Line.log("dim", " Apps    : " + String.join(",", apps)));
        out.add(Line.log("dim", " Actions : " + String.join(",", actions)));
        out.add(Line.log("dim", "============================================"));
        out.add(Line.log("ink", stars("PLAY [stg-" + group + "]")));
        out.add(Line.log("task", stars("TASK [Gathering Facts]")));
        for (String h : hosts) out.add(Line.log("ok", "ok: [" + h + "]"));

        for (String a : actions) {
            for (String app : apps) {
                String h = hostOf.apply(app);
                String jar = inv.jarFor(group, app).orElse(app + "-1.0.jar");
                out.add(Line.log("task", stars("TASK [" + a + " : " + app + " @ " + h + "]")));
                long pid = pid("stg" + group + h + app);
                String text = switch (a) {
                    case "stop" -> "changed: [" + h + "] => " + app + " pid " + pid + " stopped";
                    case "deploy" -> "changed: [" + h + "] => " + jar
                            + " -> /home/" + app + "/was/ (backup: " + jar + ".1753257821~)";
                    default -> "changed: [" + h + "] => " + app + " started, pid "
                            + pid("stg" + group + h + app + "n") + " — verified running";
                };
                out.add(Line.host("ch", text, h, a, "done"));
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        for (String h : hosts) {
            long appsOnHost = apps.stream().filter(app -> hostOf.apply(app).equals(h)).count();
            long changed = actions.size() * appsOnHost;
            out.add(Line.log("ok", pad(h) + ": ok=" + (1 + changed) + "  changed=" + changed
                    + "  unreachable=0  failed=0"));
        }
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
     * Read the {@code git.commit.id.abbrev} of the jar currently deployed for each app under
     * {@code /home/<app>/was/<jar>} on its staging host — the same value {@code hash-check.sh}
     * prints. Apps are grouped by host and read with a single {@code ansible ... -m shell} ad-hoc
     * per host (run from the bundle so its {@code ansible.cfg}/inventory apply); the per-app jar
     * hash is emitted as {@code __HASH__<app>\t<hash>} lines we parse back out of ansible's output.
     * Returns app → hash; an app missing from the map (or mapped to blank) means the hash could
     * not be read. Real mode only — never touches production.
     */
    public java.util.Map<String, String> readDeployedHashes(java.util.List<StgInventory.App> apps)
            throws IOException, InterruptedException {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        // Group apps by the staging host they live on (one for core/portal, several for npsb-zone).
        java.util.Map<String, java.util.List<StgInventory.App>> byHost = new java.util.LinkedHashMap<>();
        for (StgInventory.App a : apps) {
            byHost.computeIfAbsent(a.host(), h -> new ArrayList<>()).add(a);
        }
        for (var entry : byHost.entrySet()) {
            String host = entry.getKey();
            if (host == null || host.isBlank()) continue;
            StringBuilder script = new StringBuilder();
            for (StgInventory.App a : entry.getValue()) {
                String jarPath = "/home/" + a.key() + "/was/" + a.jar();
                // Emit __HASH__<app>\t<hash> so the app key survives ansible's per-host output framing.
                script.append("h=$(unzip -p ").append(shq(jarPath))
                        .append(" BOOT-INF/classes/git.properties 2>/dev/null | tr -d '\\r'")
                        .append(" | sed -n 's/^git\\.commit\\.id\\.abbrev=//p' | head -n1); ")
                        .append("printf '__HASH__%s\\t%s\\n' ").append(shq(a.key())).append(" \"$h\"; ");
            }
            String remote = "cd " + shq(stgDir) + " && ansible " + shq(host)
                    + " -m shell -a " + shq(script.toString()) + " 2>&1";
            String out = capture(remote, 120);
            if (out == null) continue;
            for (String line : out.split("\n")) {
                String t = line.trim();
                int tab = t.indexOf('\t');
                if (t.startsWith("__HASH__") && tab > 8) {
                    String app = t.substring("__HASH__".length(), tab).trim();
                    String hash = t.substring(tab + 1).trim();
                    if (hash.matches("[0-9a-fA-F]{7,40}")) result.put(app, hash);
                }
            }
        }
        return result;
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

    // ---- post-action process verification (read-only) ---------------------------------------

    /** One service to confirm on its staging host after a stop/start: the Linux service user
     *  (the app key, matching {@code /home/<app>/was}), the jar it runs, and the host. */
    public record VerifyTarget(String app, String jar, String host) {}

    /** Live process-table reading for one target: whether a JVM for its jar is running, the pid
     *  count seen, and whether the host could be read at all ({@code reached=false} → unverified). */
    public record ProcState(String app, String host, boolean running, int pids, boolean reached) {}

    /**
     * After a stop/start run, independently read the live process table on each staging host and
     * confirm the service really is up ({@code expectRunning=true}) or really is gone. Ansible's
     * {@code changed: … started} line only means the start command fired — a slow JVM may still be
     * coming up (or already dead) — so this polls {@code ps -u <app>} for the jar with backoff up
     * to a bounded deadline and streams a VERIFY task plus per-host ok/fatal lines. Purely
     * read-only: it never touches the service. Returns {@code host:app -> ProcState}. Real mode only.
     */
    public java.util.Map<String, ProcState> verifyProcesses(List<VerifyTarget> targets, boolean expectRunning,
                                                             Consumer<Line> sink) throws IOException, InterruptedException {
        java.util.Map<String, ProcState> state = new java.util.LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) return state;

        int timeout = expectRunning ? verifyStartTimeout : verifyStopTimeout;
        String what = expectRunning ? "started" : "stopped";
        sink.accept(Line.log("task", stars("TASK [verify : confirm " + what
                + " on host — reading process table (up to " + timeout + "s)]")));
        for (VerifyTarget t : targets) {
            sink.accept(Line.host("task", "verifying " + t.app() + " on " + t.host() + "…",
                    t.host(), "verify", "active"));
        }

        java.util.Map<String, List<VerifyTarget>> byHost = new java.util.LinkedHashMap<>();
        for (VerifyTarget t : targets) byHost.computeIfAbsent(t.host(), h -> new ArrayList<>()).add(t);

        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        while (true) {
            boolean allSettled = true;
            for (var e : byHost.entrySet()) {
                java.util.Map<String, Integer> counts = readProcCounts(e.getKey(), e.getValue());
                for (VerifyTarget t : e.getValue()) {
                    Integer c = counts.get(t.app());
                    boolean reached = c != null;
                    int pids = c == null ? 0 : c;
                    boolean running = pids > 0;
                    state.put(t.host() + ":" + t.app(), new ProcState(t.app(), t.host(), running, pids, reached));
                    if (!(reached && (expectRunning ? running : !running))) allSettled = false;
                }
            }
            if (allSettled || System.nanoTime() >= deadline) break;
            for (ProcState s : state.values()) {
                if (!(s.reached() && (expectRunning ? s.running() : !s.running()))) {
                    sink.accept(Line.log("dim", "  … " + s.app() + " @ " + s.host()
                            + (s.reached() ? " " + s.pids() + " proc" : " (host not read yet)") + " — waiting"));
                }
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            long sleep = Math.min(verifyInterval * 1000L, remainingMs);
            if (sleep <= 0) break;
            Thread.sleep(sleep);
        }

        for (VerifyTarget t : targets) {
            ProcState s = state.getOrDefault(t.host() + ":" + t.app(),
                    new ProcState(t.app(), t.host(), false, 0, false));
            if (!s.reached()) {
                sink.accept(Line.host("ch", "UNVERIFIED: [" + t.host() + "] => " + t.app()
                        + " — could not read the process table on host", t.host(), "verify", "fail"));
            } else if (expectRunning ? s.running() : !s.running()) {
                sink.accept(Line.host("ok", expectRunning
                        ? "ok: [" + t.host() + "] => " + t.app() + " RUNNING (" + s.pids()
                            + " proc) — verified on host"
                        : "ok: [" + t.host() + "] => " + t.app() + " STOPPED (no process) — verified on host",
                        t.host(), "verify", "done"));
            } else {
                sink.accept(Line.host("fatal", expectRunning
                        ? "fatal: [" + t.host() + "] => " + t.app() + " NOT RUNNING after " + timeout
                            + "s — no process found on host"
                        : "fatal: [" + t.host() + "] => " + t.app() + " STILL RUNNING (" + s.pids()
                            + " proc) after " + timeout + "s",
                        t.host(), "verify", "fail"));
            }
        }
        return state;
    }

    /** Read the JVM pid count of each target app on one host via a single ad-hoc ansible shell —
     *  {@code ps -u <app> -o args= | grep -F <jar>}, scoped to the dedicated service user so
     *  co-located services that share a jar name (sysgw/dmsgw/callcentergw) stay distinct. Returns
     *  app → count; an app absent means the host/command could not be read (→ unverified). */
    private java.util.Map<String, Integer> readProcCounts(String host, List<VerifyTarget> targets)
            throws IOException, InterruptedException {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (host == null || host.isBlank()) return out;
        StringBuilder script = new StringBuilder();
        for (VerifyTarget t : targets) {
            script.append("c=$(ps -u ").append(shq(t.app())).append(" -o args= 2>/dev/null | grep -F -- ")
                  .append(shq(t.jar())).append(" | wc -l); printf '__PROC__%s\\t%s\\n' ")
                  .append(shq(t.app())).append(" \"$c\"; ");
        }
        String remote = "cd " + shq(stgDir) + " && ansible " + shq(host)
                + " -m shell -a " + shq(script.toString()) + " 2>&1";
        String raw;
        try {
            raw = capture(remote, 60);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return out; // host unreachable / command failed — leave every app unread (unverified)
        }
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("__PROC__")) {
                int tab = t.indexOf('\t');
                if (tab > 8) {
                    String app = t.substring("__PROC__".length(), tab).trim();
                    try { out.put(app, Integer.parseInt(t.substring(tab + 1).trim())); }
                    catch (NumberFormatException ignore) { /* skip malformed */ }
                }
            }
        }
        return out;
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
