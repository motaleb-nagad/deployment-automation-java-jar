package com.nagad.deploy.service;

import com.nagad.deploy.dto.Dtos.PropTermLine;
import com.nagad.deploy.dto.Dtos.PropertiesRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces the console stream for a properties edit, mirroring {@code properties.sh} /
 * {@code properties.yml} (surgical in-place editing of {@code application.properties}).
 * One service (app), one operation — but across <em>one or more hosts</em> (e.g. app1..app6):
 * the op runs on each selected host. In demo mode ({@code nagad.ansible.simulate}) it scripts
 * the wrapper's output per host; in production it SSHes to the jump host — staging any pasted
 * block into a file first (removing a previous one, then creating it fresh) — and runs the real
 * wrapper once per host.
 */
@Component
public class PropertiesRunner {

    public static final List<String> OPS = List.of(
            "preview", "update", "add_csv", "remove_csv", "rename", "append", "insert", "diff", "restore");
    /** Ops that take a pasted block of lines (staged to a file on the server). */
    public static final List<String> BLOCK_OPS = List.of("append", "insert");
    /** Ops that mutate the file (need write/execute; everything else is read-only).
     *  {@code restore} reverts the live file from its backup, so it is execute-class too. */
    public static final List<String> WRITE_OPS = List.of(
            "update", "add_csv", "remove_csv", "rename", "append", "insert", "restore");

    public static final String CFG_TEMPLATE = "/home/<app>/cfg/application.properties";

    private final AnsibleRunner ansible;
    private final FleetInventory inv;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public PropertiesRunner(AnsibleRunner ansible, FleetInventory inv) {
        this.ansible = ansible;
        this.inv = inv;
    }

    public static String cfgFile(String app) { return "/home/" + app + "/cfg/application.properties"; }

    public static String backupFile(String app) { return cfgFile(app) + ".bkp.ansible"; }

    /** Stable per-app path a pasted block is staged to (removed + recreated each run, shared
     *  across the selected hosts within one request). */
    public String blockFilePath(String app) {
        return "/tmp/props-block-" + app + ".txt";
    }

    /** The pasted block split into lines (a trailing blank line is dropped). */
    public static List<String> blockLines(String block) {
        if (block == null || block.isBlank()) return List.of();
        String[] parts = block.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>(List.of(parts));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    private static String hostExpr(List<String> hosts) {
        return String.join(",", hosts);
    }

    /** The wrapper command line, exactly as it would be typed for one host (args identical
     *  across hosts). {@code hostToken} is a single host, or the comma list for display. */
    public String command(String hostToken, PropertiesRequest r, String blockPath) {
        StringBuilder sb = new StringBuilder("./properties.sh ")
                .append(hostToken).append(' ').append(r.app()).append(' ').append(r.op());
        switch (r.op()) {
            case "preview" -> { if (notBlank(r.key())) sb.append(' ').append(dq(r.key())); }
            case "update" -> sb.append(' ').append(dq(r.oldLine())).append(' ').append(dq(r.newLine()));
            case "add_csv", "remove_csv" -> sb.append(' ').append(dq(r.key())).append(' ').append(dq(r.values()));
            case "rename" -> sb.append(' ').append(dq(r.oldKey())).append(' ').append(dq(r.newKey()));
            case "append" -> { for (String l : blockLines(r.block())) sb.append(' ').append(shq(l)); }
            case "insert" -> {
                sb.append(' ').append(dq(r.afterLine()));
                for (String l : blockLines(r.block())) sb.append(' ').append(shq(l));
            }
            default -> { /* diff | restore — no args */ }
        }
        if (r.testMode()) sb.append(" --test");
        return sb.toString();
    }

    // ---- demo mode (scripted output) --------------------------------------------------------

    public List<PropTermLine> script(PropertiesRequest r, String blockPath, boolean changed) {
        String app = r.app(), op = r.op();
        boolean test = r.testMode();
        String file = cfgFile(app);
        List<String> hosts = r.hosts();
        List<PropTermLine> out = new ArrayList<>();

        out.add(line("user", "$ " + command(hostExpr(hosts), r, blockPath)
                + (hosts.size() > 1 ? "   # run per host" : "")));
        out.add(line("dim", "============================================"));
        out.add(line(test ? "task" : "ink", test ? " PROPERTIES EDIT — TEST MODE (real file NOT modified)" : " PROPERTIES EDIT"));
        out.add(line("dim", " Hosts    : " + hosts.stream().map(h -> "nagad-" + h).toList()));
        out.add(line("dim", " App      : " + app));
        out.add(line("dim", " Operation: " + op));
        out.add(line("dim", " File     : " + file));
        if (test) out.add(line("dim", " Test file: " + file + ".test.ansible"));
        out.add(line("dim", "============================================"));

        List<String> block = blockLines(r.block());
        String stamp = STAMP.format(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());

        for (String host : hosts) {
            perHost(out, host, r, changed, block, stamp);
        }

        out.add(line("ink", stars("PLAY RECAP")));
        for (String host : hosts) {
            out.add(line("ok", pad("nagad-" + host) + ": ok=2  changed=" + (changed ? 1 : 0) + "  unreachable=0  failed=0"));
        }
        if (test) out.add(line("task", "TEST MODE — real file was NOT modified. Review the diff, then re-run without --test."));
        out.add(line("dim", "Report emailed to devops-team@nagad.com.bd"));
        return out;
    }

    private void perHost(List<PropTermLine> out, String host, PropertiesRequest r,
                         boolean changed, List<String> block, String stamp) {
        String app = r.app(), op = r.op();
        boolean test = r.testMode();
        String file = cfgFile(app);
        out.add(line("ink", stars("PLAY [properties — " + host + "]")));
        out.add(line("task", stars("TASK [GUARD — app / op / file exist]")));
        out.add(line("ok", "ok: [nagad-" + host + "]"));

        switch (op) {
            case "preview" -> scriptPreview(out, host, app, r.key());
            case "update" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [update — replace exact line]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => 1 line replaced" + (test ? " (test copy)" : "")));
                diffBlock(out, List.of(r.oldLine()), List.of(r.newLine()));
            }
            case "add_csv" -> {
                backup(out, host, app, test);
                String before = fakeCsv(app, r.key());
                String after = before + "," + safe(r.values());
                out.add(line("task", stars("TASK [add_csv — append to comma value]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => " + r.key()));
                diffBlock(out, List.of(r.key() + "=" + before), List.of(r.key() + "=" + after));
            }
            case "remove_csv" -> {
                backup(out, host, app, test);
                String before = fakeCsv(app, r.key());
                String after = removeCsv(before, r.values());
                out.add(line("task", stars("TASK [remove_csv — drop from comma value]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => " + r.key()));
                diffBlock(out, List.of(r.key() + "=" + before), List.of(r.key() + "=" + after));
            }
            case "rename" -> {
                backup(out, host, app, test);
                String val = fakeVal(app, r.oldKey());
                out.add(line("task", stars("TASK [rename — key only, value kept]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => key renamed"));
                diffBlock(out, List.of(r.oldKey() + "=" + val), List.of(r.newKey() + "=" + val));
            }
            case "append" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [append — add block at end (date header)]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => " + (block.size() + 1) + " line(s) appended"));
                List<String> added = new ArrayList<>();
                added.add("");
                added.add("##########" + stamp + "##########");
                added.addAll(block);
                diffBlock(out, List.of(), added);
            }
            case "insert" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [insert — add block after matched line]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => " + block.size() + " line(s) inserted"));
                out.add(line("dim", "  after: " + r.afterLine()));
                diffBlock(out, List.of(), block);
            }
            case "diff" -> {
                out.add(line("task", stars("TASK [diff — current vs .bkp.ansible]")));
                if (inv.h32(host + app) % 2 == 0) {
                    out.add(line("ok", "ok: [nagad-" + host + "] => no differences from last backup"));
                } else {
                    out.add(line("ch", "changed: [nagad-" + host + "] => differences found:"));
                    diffBlock(out, List.of("server.port=" + (10030 + inv.h32(app) % 40)),
                            List.of("server.port=" + (10030 + inv.h32(app + "n") % 40)));
                }
            }
            case "restore" -> {
                out.add(line("task", stars("TASK [restore — copy .bkp.ansible over live file]")));
                out.add(line("ch", "changed: [nagad-" + host + "] => restored " + file + " from " + backupFile(app)));
            }
            default -> { /* validated upstream */ }
        }
    }

    private void scriptPreview(List<PropTermLine> out, String host, String app, String key) {
        if (notBlank(key)) {
            out.add(line("task", stars("TASK [preview — grep \"" + key + "\"]")));
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                if (inv.h32(app + key + i) % 3 == 0) continue;
                any = true;
                int ln = 40 + (int) (inv.h32(app + key + i) % 90);
                out.add(line("ink", "  [" + host + "] " + ln + ": " + key + (i == 0 ? "" : "." + i) + "=" + (inv.h32(app + key + i) % 500)));
            }
            if (!any) out.add(line("dim", "  [" + host + "] (no line matched \"" + key + "\")"));
        } else {
            out.add(line("task", stars("TASK [preview — full file with line numbers]")));
            String[] sample = {
                    "server.port=" + (10030 + inv.h32(app) % 40),
                    "spring.application.name=" + app,
                    "spring.datasource.hikari.maximum-pool-size=" + (20 + inv.h32(app + "p") % 80),
                    "http.client.pool.socket-timeout=" + (300000 + inv.h32(app + "t") % 200000),
                    "logging.level.root=INFO",
            };
            for (int i = 0; i < sample.length; i++) out.add(line("ink", "  [" + host + "] " + (i + 1) + ": " + sample[i]));
            out.add(line("dim", "  [" + host + "] … (" + (60 + inv.h32(app) % 120) + " lines total)"));
        }
    }

    private void backup(List<PropTermLine> out, String host, String app, boolean test) {
        if (test) {
            out.add(line("task", stars("TASK [test — copy real file to .test.ansible]")));
            out.add(line("ok", "ok: [nagad-" + host + "] => editing " + cfgFile(app) + ".test.ansible"));
        } else {
            out.add(line("task", stars("TASK [backup — .bkp.ansible before write]")));
            out.add(line("ch", "changed: [nagad-" + host + "] => " + backupFile(app)));
        }
    }

    private void diffBlock(List<PropTermLine> out, List<String> removed, List<String> added) {
        for (String s : removed) out.add(line("del", "- " + s));
        for (String s : added) out.add(line("add", "+ " + s));
    }

    // ---- real execution (production) --------------------------------------------------------

    /**
     * SSH to the jump host and run the real wrapper once per selected host. Block ops
     * (append/insert) pass their pasted lines inline as single-quoted arguments — no temp file
     * is staged on, or left behind on, the server. {@code properties.yml} backs up the live file
     * (to {@code .bkp.ansible} plus a timestamped copy) before it writes. Returns the classified
     * console output.
     */
    public List<PropTermLine> execute(PropertiesRequest r) throws IOException, InterruptedException {
        String wd = shq(ansible.workingDir());
        List<PropTermLine> out = new ArrayList<>();

        for (String host : r.hosts()) {
            String cmd = command(host, r, null);
            out.add(line("user", "$ " + cmd));
            String remote = "cd " + wd + " && " + cmd;
            String raw = ansible.capture(remote, 120);
            for (String l : raw.split("\n", -1)) out.add(classify(l));
        }
        return out;
    }

    private static PropTermLine classify(String text) {
        if (text == null) return line("ink", "");
        String t = text;
        if (t.startsWith("+ ")) return line("add", t);
        if (t.startsWith("- ")) return line("del", t);
        if (t.startsWith("changed:")) return line("ch", t);
        if (t.startsWith("ok:")) return line("ok", t);
        if (t.startsWith("PLAY RECAP") || t.startsWith("PLAY [")) return line("ink", t);
        if (t.startsWith("TASK [")) return line("task", t);
        if (t.startsWith("fatal") || t.startsWith("ERROR") || t.contains("FAILED")) return line("fatal", t);
        return line("ink", t);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static PropTermLine line(String level, String text) { return new PropTermLine(level, text); }

    private String fakeCsv(String app, String key) {
        long h = inv.h32(app + key);
        return (h % 90) + "," + ((h >> 3) % 90) + "," + ((h >> 6) % 90);
    }

    private String fakeVal(String app, String key) { return Long.toString(inv.h32(app + key) % 1000); }

    private static String removeCsv(String csv, String values) {
        List<String> drop = List.of(safe(values).split(","));
        List<String> kept = new ArrayList<>();
        for (String p : csv.split(",")) if (!drop.contains(p.trim())) kept.add(p.trim());
        return String.join(",", kept);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }

    /** Double-quote for display (matches how the wrapper is typed). */
    private static String dq(String s) { return "\"" + safe(s) + "\""; }

    /** Single-quote for a POSIX shell, escaping embedded single quotes. */
    private static String shq(String s) { return "'" + safe(s).replace("'", "'\\''") + "'"; }

    private static String stars(String t) { return t + " " + "*".repeat(Math.max(6, 66 - t.length())); }

    private static String pad(String h) { return (h + "                ").substring(0, 16); }
}
