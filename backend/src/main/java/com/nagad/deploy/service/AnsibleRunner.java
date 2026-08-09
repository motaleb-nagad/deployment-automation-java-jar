package com.nagad.deploy.service;

import com.nagad.deploy.dto.Dtos.DeployPair;
import com.nagad.deploy.service.FleetInventory.Group;
import com.nagad.deploy.service.FleetInventory.Svc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the console stream for a run. In demo mode ({@code nagad.ansible.simulate}) it
 * builds scripted Ansible output line-by-line, exactly what the wrapper prints; in production
 * it SSHes into the jump host as the ansible operator and invokes the real {@code ./run.sh},
 * forwarding its stdout through the same {@link Line} channel.
 */
@Component
public class AnsibleRunner {

    private static final Logger log = LoggerFactory.getLogger(AnsibleRunner.class);

    /** level: user | dim | ink | task | ok | ch | fatal. rail fields drive the per-host tracker. */
    public record Line(String level, String text, String railHost, String railAction, String railState) {
        static Line log(String level, String text) { return new Line(level, text, null, null, null); }
        static Line host(String level, String text, String host, String action, String state) {
            return new Line(level, text, host, action, state);
        }
    }

    private final String workingDir;
    private final String portalUiDir;
    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String sshKeyPath;
    private final boolean strictHostKey;

    // Post-action process verification (read-only) — how long to wait for a JVM to come up on
    // start / drain on stop, and how often to re-read the process table in between.
    private final boolean verifyEnabled;
    private final int verifyStartTimeout;
    private final int verifyStopTimeout;
    private final int verifyInterval;

    // The mounted key is copied once to a process-owned 600 file so ssh's ownership/mode
    // check passes regardless of how the key is mounted into the container.
    private volatile Path readyKey;

    public AnsibleRunner(
            @Value("${nagad.ansible.working-dir}") String workingDir,
            @Value("${nagad.ansible.portal-ui.working-dir:/home/konasl/motaleb-ansible/portalui-deployment}") String portalUiDir,
            @Value("${nagad.ansible.ssh.host:host.docker.internal}") String sshHost,
            @Value("${nagad.ansible.ssh.port:40167}") int sshPort,
            @Value("${nagad.ansible.ssh.user:konasl}") String sshUser,
            @Value("${nagad.ansible.ssh.key:/run/secrets/deploy_key}") String sshKeyPath,
            @Value("${nagad.ansible.ssh.strict-host-key-checking:false}") boolean strictHostKey,
            @Value("${nagad.verify.enabled:true}") boolean verifyEnabled,
            @Value("${nagad.verify.start-timeout-seconds:180}") int verifyStartTimeout,
            @Value("${nagad.verify.stop-timeout-seconds:45}") int verifyStopTimeout,
            @Value("${nagad.verify.interval-seconds:6}") int verifyInterval) {
        this.workingDir = workingDir;
        this.portalUiDir = portalUiDir;
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

    /** Whether post-action process verification is switched on (nagad.verify.enabled). */
    public boolean verifyEnabled() { return verifyEnabled; }

    public String command(String cmd, List<String> hosts, List<String> apps, List<String> actions) {
        // No -K: the managed deploy playbooks run with become: false, so runs are non-interactive.
        return cmd + " " + hostExpr(hosts) + " " + String.join(",", apps) + " " + String.join(",", actions);
    }

    public static String hostExpr(List<String> hosts) {
        if (hosts.isEmpty()) return "all";
        if (hosts.size() == 1) return hosts.get(0);
        return hosts.get(0) + ".." + hosts.get(hosts.size() - 1);
    }

    /** The consolidated wrapper command: {@code ./deploy.sh "host:app host:app" actions}. */
    public String consolidatedCommand(List<DeployPair> pairs, List<String> actions) {
        String tokens = pairs.stream().map(p -> p.host() + ":" + p.app())
                .collect(java.util.stream.Collectors.joining(" "));
        return "./deploy.sh \"" + tokens + "\" " + String.join(",", actions);
    }

    // ---- consolidated real execution (production) -------------------------------------------

    /**
     * SSH to the jump host and run the consolidated {@code ./deploy.sh} wrapper for the given
     * host:app pairs, streaming each stdout line to {@code sink}. Returns the wrapper's exit code.
     */
    public int executeConsolidated(List<DeployPair> pairs, List<String> actions, Consumer<Line> sink)
            throws IOException, InterruptedException {
        String tokens = pairs.stream().map(p -> p.host() + ":" + p.app())
                .collect(java.util.stream.Collectors.joining(" "));
        String actionsCsv = String.join(",", actions);

        String remote = "cd " + shq(workingDir) + " && ./deploy.sh " + shq(tokens) + " " + shq(actionsCsv);
        sink.accept(Line.log("user", "$ ./deploy.sh \"" + tokens + "\" " + actionsCsv));

        ProcessBuilder pb = new ProcessBuilder(sshArgv(remote));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String currentAction = firstActionOf(actions);
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

    // ---- real execution (production) --------------------------------------------------------

    /**
     * SSH to the jump host and run the wrapper, streaming each stdout line to {@code sink}.
     * The become-password ({@code -K}) is intentionally omitted: the managed deploy playbooks
     * run with {@code become: false}, so the run is fully non-interactive. Returns the wrapper's
     * exit code (0 = success).
     */
    public int execute(String group, List<String> hosts, List<String> apps, List<String> actions,
                       Consumer<Line> sink) throws IOException, InterruptedException {
        String servers = hostExpr(hosts);
        String appsCsv = String.join(",", apps);
        String actionsCsv = String.join(",", actions);

        String remote = "cd " + shq(workingDir) + " && ./run.sh "
                + shq(group) + " " + shq(servers) + " " + shq(appsCsv) + " " + shq(actionsCsv);

        sink.accept(Line.log("user", "$ ./run.sh " + group + " " + servers + " " + appsCsv + " " + actionsCsv));

        ProcessBuilder pb = new ProcessBuilder(sshArgv(remote));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String currentAction = firstActionOf(actions);
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

    // ---- portal-ui channel ------------------------------------------------------------------

    /** The portal-ui run.sh command line: {@code ./run.sh <uis> [date] [--mode] [--no-*] [-h hosts]}. */
    public String portalUiCommand(String mode, List<String> uis, List<String> hosts,
                                  boolean fixUrl, boolean fixSize, String date) {
        StringBuilder sb = new StringBuilder("./run.sh ").append(String.join(",", uis));
        if (date != null && !date.isBlank()) sb.append(' ').append(date.trim());
        switch (mode) {
            case "fetch" -> sb.append(" --fetch");
            case "rollback" -> sb.append(" --rollback");
            case "verify" -> sb.append(" --verify");
            default -> { /* deploy: no mode flag */ }
        }
        if ("deploy".equals(mode)) {
            if (!fixUrl) sb.append(" --no-url-fix");
            if (!fixSize) sb.append(" --no-size-fix");
        }
        if (("deploy".equals(mode) || "rollback".equals(mode)) && hosts != null && !hosts.isEmpty()) {
            sb.append(" -h ").append(String.join(",", hosts));
        }
        return sb.toString();
    }

    /** SSH to the jump host and run the portal-ui wrapper, streaming stdout. Returns exit code. */
    public int executePortalUi(String mode, List<String> uis, List<String> hosts,
                               boolean fixUrl, boolean fixSize, String date, Consumer<Line> sink)
            throws IOException, InterruptedException {
        String cmd = portalUiCommand(mode, uis, hosts, fixUrl, fixSize, date);
        String remote = "cd " + shq(portalUiDir) + " && " + cmd;
        sink.accept(Line.log("user", "$ " + cmd));

        ProcessBuilder pb = new ProcessBuilder(sshArgv(remote));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String currentAction = mode;
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

    /** Rail phases shown per host for a portal-ui mode (kept in sync with the frontend). */
    public static List<String> portalUiPhases(String mode) {
        return switch (mode) {
            case "fetch" -> List.of("fetch");
            case "rollback" -> List.of("snapshot", "rollback");
            case "verify" -> List.of("verify");
            default -> List.of("backup", "deploy", "fixes"); // deploy
        };
    }

    /** Demo mode: scripted portal-ui output per host×ui for the chosen mode. */
    public List<Line> scriptPortalUi(String mode, List<String> uis, List<String> hosts,
                                     boolean fixUrl, boolean fixSize, String date,
                                     FleetInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        String play = switch (mode) {
            case "fetch" -> "PLAY [portal-ui fetch — stg-portal]";
            case "rollback" -> "PLAY [portal-ui rollback — nagad-web-dmz]";
            case "verify" -> "PLAY [portal-ui verify — fetched vs staging]";
            default -> "PLAY [portal-ui deploy — nagad-web-dmz]";
        };
        out.add(Line.log("ink", stars(play)));
        out.add(Line.log("task", stars("TASK [Validate ui names]")));
        for (String h : hosts) out.add(Line.log("ok", "ok: [nagad-" + h + "]"));

        String bkpDate = (date == null || date.isBlank()) ? "today" : date.trim();
        for (String ui : uis) {
            switch (mode) {
                case "fetch" -> {
                    out.add(Line.log("task", stars("TASK [fetchui : " + ui + " from staging]")));
                    for (String h : hosts) out.add(Line.host("ch", "changed: [" + h + "] => pulled "
                            + ui + " → roles/portalui/files/" + ui + ".tar.gz (md5 "
                            + Long.toHexString(inv.h32("pui" + ui)).substring(0, 8) + ")", h, "fetch", "done"));
                }
                case "verify" -> {
                    out.add(Line.log("task", stars("TASK [verify : " + ui + "]")));
                    boolean match = inv.h32("puiverify" + ui) % 2 == 0;
                    for (String h : hosts) out.add(Line.host(match ? "ok" : "ch",
                            (match ? "ok" : "changed") + ": [" + h + "] => " + ui
                                    + (match ? " matches live staging" : " DIFFERS from live staging (see report)"),
                            h, "verify", "done"));
                }
                case "rollback" -> {
                    for (String h : hosts) {
                        out.add(Line.log("task", stars("TASK [snapshot current " + ui + " @ " + h + "]")));
                        out.add(Line.host("ch", "changed: [nagad-" + h + "] => saved "
                                + ui + ".prerollback." + inv.h32(ui + h) + ".tar.gz", h, "snapshot", "done"));
                        out.add(Line.log("task", stars("TASK [rollback " + ui + " @ " + h + "]")));
                        out.add(Line.host("ch", "changed: [nagad-" + h + "] => restored "
                                + ui + " from backup " + bkpDate, h, "rollback", "done"));
                    }
                }
                default -> { // deploy
                    for (String h : hosts) {
                        out.add(Line.log("task", stars("TASK [backup " + ui + " @ " + h + "]")));
                        out.add(Line.host("ch", "changed: [nagad-" + h + "] => ui/backup/"
                                + ui + "." + bkpDate + ".tar.gz", h, "backup", "done"));
                        out.add(Line.log("task", stars("TASK [deploy " + ui + " @ " + h + "]")));
                        out.add(Line.host("ch", "changed: [nagad-" + h + "] => extracted "
                                + ui + ".tar.gz → /usr/local/nginx/html/ui/" + ui, h, "deploy", "done"));
                        if (fixUrl || fixSize) {
                            out.add(Line.log("task", stars("TASK [urlfix " + ui + " @ " + h + "]")));
                            String detail = (fixUrl ? "host swap OK" : "url-fix skipped")
                                    + " · " + (fixSize ? "MAX_FILE_SIZE=10485760" : "size-fix skipped");
                            out.add(Line.host("ch", "changed: [nagad-" + h + "] => " + detail, h, "fixes", "done"));
                        }
                    }
                }
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        for (String h : hosts) out.add(Line.log("ok", pad(h) + ": ok=1  changed="
                + (uis.size() * portalUiPhases(mode).size()) + "  unreachable=0  failed=0"));
        out.add(Line.log("dim", "Report emailed to devops-team@nagad.com.bd"));
        return out;
    }

    /** The working directory the wrapper + inventory live in on the jump host. */
    public String workingDir() { return workingDir; }

    /**
     * Pull a jar from a staging host with {@code ./fetch.sh} and read its real
     * {@code git.commit.id.abbrev} out of {@code BOOT-INF/classes/git.properties} — the exact
     * value {@code hash-check.sh} prints. This is what the FETCH screen shows, so the console's
     * hash matches the jar on disk. Returns empty if the pull/read fails (the caller then falls
     * back to a deterministic stand-in). Used only in real (non-simulate) mode.
     */
    public Optional<String> stagingGitHash(String srcHost, String app, String jar) {
        String jarPath = "roles/deployment/files/jars/" + jar;
        // fetch.sh pulls the jar into files/jars/ (removing the old local copy first), then we
        // read the embedded git hash from it — never touching production.
        String remote = "cd " + shq(workingDir) + " && ./fetch.sh " + shq(app) + " " + shq(srcHost)
                + " >/dev/null 2>&1; unzip -p " + shq(jarPath)
                + " BOOT-INF/classes/git.properties 2>/dev/null | tr -d '\\r'"
                + " | sed -n 's/^git\\.commit\\.id\\.abbrev=//p' | head -n1";
        try {
            String out = capture(remote, 120);
            // The sed prints the bare abbrev hash on its own line; pick the last line that is a
            // pure hex token so any leaked ssh notice ("Warning: …", "** … post-quantum …") or a
            // failed/empty fetch can never be mistaken for the hash.
            String hash = null;
            if (out != null) {
                for (String line : out.split("\n")) {
                    String t = line.trim();
                    if (t.matches("[0-9a-fA-F]{7,40}")) hash = t;
                }
            }
            return (hash == null || hash.isBlank()) ? Optional.empty() : Optional.of(hash);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("staging git-hash read failed for {} on {}: {}", app, srcHost, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Run a command on the jump host over SSH and return its merged stdout/stderr. Used by the
     * fleet collector to shell out to {@code ansible ... -m ping / -m shell} for real status.
     * Enforces a hard timeout so a hung host can never wedge the collector.
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
        if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("jump-host command timed out after " + timeoutSeconds + "s");
        }
        return out.toString();
    }

    // ---- post-action process verification (read-only) ---------------------------------------

    /** One service to confirm on its host after a stop/start: the Linux service user (the app
     *  key, matching {@code /home/<app>/was}), the jar it runs, and the SHORT host name used on
     *  the rail/rows (the ansible inventory name is derived by prefixing {@code nagad-}). */
    public record VerifyTarget(String app, String jar, String host) {}

    /** Live process-table reading for one target: whether a JVM for its jar is running, the pid
     *  count seen, and whether the host could be read at all ({@code reached=false} → unverified). */
    public record ProcState(String app, String host, boolean running, int pids, boolean reached) {}

    /**
     * After a stop/start run, independently read the live process table on each target host and
     * confirm the service really is up ({@code expectRunning=true}) or really is gone. Ansible's
     * {@code changed: … started} line only means the start command fired — a slow JVM may still be
     * coming up (or already dead) — so this polls {@code ps -u <app>} for the jar with backoff up
     * to a bounded deadline and streams a VERIFY task plus per-host ok/fatal lines to the console.
     * Purely read-only: it never touches the service. Returns {@code host:app -> ProcState}.
     * Real mode only.
     */
    public Map<String, ProcState> verifyProcesses(List<VerifyTarget> targets, boolean expectRunning,
                                                  Consumer<Line> sink) throws IOException, InterruptedException {
        Map<String, ProcState> state = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) return state;

        int timeout = expectRunning ? verifyStartTimeout : verifyStopTimeout;
        String what = expectRunning ? "started" : "stopped";
        sink.accept(Line.log("task", stars("TASK [verify : confirm " + what
                + " on host — reading process table (up to " + timeout + "s)]")));
        for (VerifyTarget t : targets) {
            sink.accept(Line.host("task", "verifying " + t.app() + " on " + t.host() + "…",
                    t.host(), "verify", "active"));
        }

        Map<String, List<VerifyTarget>> byHost = new LinkedHashMap<>();
        for (VerifyTarget t : targets) byHost.computeIfAbsent(t.host(), h -> new ArrayList<>()).add(t);

        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        while (true) {
            boolean allSettled = true;
            for (var e : byHost.entrySet()) {
                Map<String, Integer> counts = readProcCounts(e.getKey(), e.getValue());
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
     *  co-located services that share a jar name stay distinct. Returns app → count; an app
     *  absent from the map means the host/command could not be read (→ unverified). */
    private Map<String, Integer> readProcCounts(String host, List<VerifyTarget> targets)
            throws IOException, InterruptedException {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (host == null || host.isBlank()) return out;
        StringBuilder script = new StringBuilder();
        for (VerifyTarget t : targets) {
            script.append("c=$(ps -u ").append(shq(t.app())).append(" -o args= 2>/dev/null | grep -F -- ")
                  .append(shq(t.jar())).append(" | wc -l); printf '__PROC__%s\\t%s\\n' ")
                  .append(shq(t.app())).append(" \"$c\"; ");
        }
        String remote = "cd " + shq(workingDir) + " && ansible " + shq(inventoryName(host))
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

    /** The ansible inventory name for a UI/rail host — prefixes {@code nagad-} (deploy.sh's
     *  resolve_host convention) unless the name already carries it. */
    private static String inventoryName(String host) {
        String h = host.trim();
        return h.startsWith("nagad-") ? h : "nagad-" + h;
    }

    /**
     * List the jars staged in {@code roles/deployment/files/jars/} (live + {@code .jar.bkp.*}
     * rollback copies) and read the git commit info embedded in each — the same data
     * {@code hash-check.sh} prints, but as parseable TSV:
     * {@code <name>\t<hash>\t<branch>\t<commitTime>\t<0|1 backup>}.
     */
    public String readStagedJars() throws IOException, InterruptedException {
        String dir = workingDir + "/roles/deployment/files/jars";
        String script =
                "cd " + shq(dir) + " 2>/dev/null || exit 0; "
                + "shopt -s nullglob; "
                + "for f in *.jar *.jar.bkp*; do "
                + "  [ -e \"$f\" ] || continue; "
                + "  p=$(unzip -p \"$f\" BOOT-INF/classes/git.properties 2>/dev/null | tr -d '\\r'); "
                + "  h=$(printf '%s\\n' \"$p\" | sed -n 's/^git.commit.id.abbrev=//p' | head -1); "
                + "  b=$(printf '%s\\n' \"$p\" | sed -n 's/^git.branch=//p' | head -1); "
                + "  t=$(printf '%s\\n' \"$p\" | sed -n 's/^git.commit.time=//p' | head -1 | sed 's/\\\\:/:/g'); "
                + "  case \"$f\" in *.jar.bkp*) bk=1;; *) bk=0;; esac; "
                + "  printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"$f\" \"$h\" \"$b\" \"$t\" \"$bk\"; "
                + "done";
        return capture(script, 60);
    }

    /** Remove one staged jar (or {@code .jar.bkp.*} copy) from files/jars/ on the ops host. The
     *  filename is validated by the caller; we still {@code --} guard and confine to that dir. */
    public void removeStagedJar(String jarFile) throws IOException, InterruptedException {
        String dir = workingDir + "/roles/deployment/files/jars";
        String remote = "cd " + shq(dir) + " && rm -f -- " + shq(jarFile) + " && echo removed";
        capture(remote, 30);
    }

    private List<String> sshArgv(String remoteCommand) throws IOException {
        List<String> argv = new ArrayList<>(List.of(
                "ssh", "-i", keyFile().toString(),
                "-p", Integer.toString(sshPort),
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                // Quieten the client so host-key/PQ notices don't pollute captured command
                // output (hash reads, fleet status). Parsers are hardened too, belt-and-braces.
                "-o", "LogLevel=ERROR",
                "-o", "StrictHostKeyChecking=" + (strictHostKey ? "yes" : "no")));
        if (!strictHostKey) { argv.add("-o"); argv.add("UserKnownHostsFile=/dev/null"); }
        argv.add(sshUser + "@" + sshHost);
        argv.add(remoteCommand);
        return argv;
    }

    private static final Pattern HOST_LINE =
            Pattern.compile("^(ok|changed|failed|fatal|unreachable|skipping):\\s*\\[([^\\]]+)\\]");

    private static Line classify(String text, String action) {
        Matcher m = HOST_LINE.matcher(text);
        if (m.find()) {
            String verb = m.group(1);
            String host = stripPrefix(m.group(2));
            String level = switch (verb) {
                case "changed" -> "ch";
                case "ok", "skipping" -> "ok";
                default -> "fatal";
            };
            String state = (verb.equals("failed") || verb.equals("fatal") || verb.equals("unreachable"))
                    ? "fail" : "done";
            return Line.host(level, text, host, action, state);
        }
        // Missing-service skips from consolidated.yml — a machine marker plus a human note.
        // Surface them as informational (never fatal); the console parses the marker.
        if (text.contains("SKIP_PAIR:") || text.contains("SKIPPED on ") || text.contains("not installed here")) {
            return Line.log("dim", text);
        }
        if (text.startsWith("PLAY RECAP") || text.startsWith("PLAY [")) return Line.log("ink", text);
        if (text.startsWith("TASK [")) return Line.log("task", text);
        if (text.startsWith("fatal") || text.startsWith("ERROR") || text.contains("FAILED")) {
            return Line.log("fatal", text);
        }
        if (text.startsWith("====") || text.startsWith(" Group") || text.startsWith(" Limit")
                || text.startsWith(" Apps") || text.startsWith(" Actions")) {
            return Line.log("dim", text);
        }
        return Line.log("ink", text);
    }

    /** Strip the inventory hostname prefix so rail keys match the UI host names (nagad-app1 -> app1). */
    private static String stripPrefix(String host) {
        String h = host.trim();
        return h.startsWith("nagad-") ? h.substring("nagad-".length()) : h;
    }

    private static String actionFromTask(String line) {
        if (!line.startsWith("TASK [")) return null;
        String l = line.toLowerCase();
        if (l.contains("stop")) return "stop";
        if (l.contains("deploy")) return "deploy";
        if (l.contains("start")) return "start";
        return null;
    }

    private static String firstActionOf(List<String> actions) {
        return actions.isEmpty() ? "" : actions.get(0);
    }

    /** Single-quote for a POSIX shell, escaping embedded single quotes. */
    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private synchronized Path keyFile() throws IOException {
        if (readyKey != null) return readyKey;
        Path src = Path.of(sshKeyPath);
        Path dst = Files.createTempFile("deploy-key-", "");
        Files.setPosixFilePermissions(dst, PosixFilePermissions.fromString("rw-------"));
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(dst, PosixFilePermissions.fromString("rw-------"));
        dst.toFile().deleteOnExit();
        readyKey = dst;
        return dst;
    }

    // ---- demo mode (scripted output) --------------------------------------------------------

    public List<Line> script(Group g, List<String> hosts, List<String> apps, List<String> actions,
                             FleetInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("ink", stars("PLAY [" + g.key() + "]")));
        out.add(Line.log("task", stars("TASK [Gathering Facts]")));
        for (String h : hosts) out.add(Line.log("ok", "ok: [nagad-" + h + "]"));

        for (String a : actions) {
            for (String app : apps) {
                Svc s = g.svcs().stream().filter(x -> x.key().equals(app)).findFirst()
                        .orElse(new Svc(app, 1, FleetInventory.JAR_MAP.getOrDefault(app, app + "-1.0.jar")));
                out.add(Line.log("task", stars("TASK [" + a + " : " + app + "]")));
                for (String h : hosts) {
                    long pid = inv.pid(g.key() + h + app);
                    String text = switch (a) {
                        case "stop" -> "changed: [nagad-" + h + "] => " + app + " pid " + pid + " stopped"
                                + (s.instances() > 1 ? " (INST_1.." + s.instances() + ")" : "");
                        case "deploy" -> "changed: [nagad-" + h + "] => " + s.jar()
                                + " -> /home/" + app + "/was/ (backup: " + s.jar() + ".1753257821~)";
                        default -> "changed: [nagad-" + h + "] => " + app + " started, pid "
                                + inv.pid(g.key() + h + app + "n") + " — verified running";
                    };
                    out.add(Line.host("ch", text, h, a, "done"));
                }
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        int perHost = 1 + actions.size() * apps.size();
        for (String h : hosts) {
            out.add(Line.log("ok", pad(h) + ": ok=" + perHost + "  changed=" + (actions.size() * apps.size())
                    + "  unreachable=0  failed=0"));
        }
        out.add(Line.log("dim", "Report emailed to devops-team@nagad.com.bd"));
        return out;
    }

    /** Demo mode for a consolidated run — scripted mixed-group output, one task per host:app pair. */
    public List<Line> scriptConsolidated(List<DeployPair> pairs, List<String> actions,
                                         FleetInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("ink", stars("PLAY [consolidated]")));
        out.add(Line.log("task", stars("TASK [Gathering Facts]")));
        List<String> hosts = new ArrayList<>();
        for (DeployPair p : pairs) if (!hosts.contains(p.host())) hosts.add(p.host());
        for (String h : hosts) out.add(Line.log("ok", "ok: [nagad-" + h + "]"));

        // Availability pre-check (mirrors consolidated.yml): announce services that are not
        // installed on their host as SKIP markers, then run only the present pairs.
        out.add(Line.log("task", stars("TASK [Detect installed services per host]")));
        List<DeployPair> present = new ArrayList<>();
        for (DeployPair p : pairs) {
            if (inv.demoPresentOnHost(p.host(), p.app())) {
                present.add(p);
            } else {
                out.add(Line.log("dim", "SKIP_PAIR: " + p.host() + ":" + p.app()));
                out.add(Line.log("dim", "SKIPPED on nagad-" + p.host()
                        + " — service not installed here, continuing without it: " + p.app()));
            }
        }

        for (String a : actions) {
            for (DeployPair p : present) {
                String app = p.app(), h = p.host();
                String jar = FleetInventory.JAR_MAP.getOrDefault(app, app + "-1.0.jar");
                out.add(Line.log("task", stars("TASK [" + a + " : " + app + " @ " + h + "]")));
                long pid = inv.pid("cons" + h + app);
                String text = switch (a) {
                    case "stop" -> "changed: [nagad-" + h + "] => " + app + " pid " + pid + " stopped";
                    case "deploy" -> "changed: [nagad-" + h + "] => " + jar
                            + " -> /home/" + app + "/was/ (backup: " + jar + ".1753257821~)";
                    default -> "changed: [nagad-" + h + "] => " + app + " started, pid "
                            + inv.pid("cons" + h + app + "n") + " — verified running";
                };
                out.add(Line.host("ch", text, h, a, "done"));
            }
        }
        out.add(Line.log("ink", stars("PLAY RECAP")));
        for (String h : hosts) {
            long appsOnHost = present.stream().filter(p -> p.host().equals(h)).count();
            long skippedOnHost = pairs.stream().filter(p -> p.host().equals(h)).count() - appsOnHost;
            long changed = actions.size() * appsOnHost;
            out.add(Line.log("ok", pad(h) + ": ok=" + (1 + changed) + "  changed=" + changed
                    + "  unreachable=0  failed=0  skipped=" + (actions.size() * skippedOnHost)));
        }
        out.add(Line.log("dim", "Report emailed to devops-team@nagad.com.bd"));
        return out;
    }

    private static String stars(String t) {
        return t + " " + "*".repeat(Math.max(6, 66 - t.length()));
    }

    private static String pad(String h) {
        return (h + "            ").substring(0, 12);
    }
}
