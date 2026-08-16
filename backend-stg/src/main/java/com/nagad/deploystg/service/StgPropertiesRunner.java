package com.nagad.deploystg.service;

import com.nagad.deploystg.dto.Dtos.StgPropTermLine;
import com.nagad.deploystg.dto.Dtos.StgPropertiesRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Staging counterpart of the production properties runner — surgical in-place editing of
 * {@code application.properties} on the single staging host of a group (core → ngd-dc-core-01,
 * portal → ngd-dc-portal-01), mapping to {@code ./properties.sh} in the {@code stg-deployment}
 * bundle. In demo mode it scripts the wrapper's output; in real mode it stages any pasted block
 * on the jump host and runs the wrapper there.
 */
@Component
public class StgPropertiesRunner {

    public static final List<String> OPS = List.of(
            "preview", "update", "add_csv", "remove_csv", "rename", "append", "insert", "diff", "restore");
    public static final List<String> BLOCK_OPS = List.of("append", "insert");
    public static final List<String> WRITE_OPS = List.of(
            "update", "add_csv", "remove_csv", "rename", "append", "insert", "restore");
    public static final String CFG_TEMPLATE = "/home/<app>/cfg/application.properties";

    private final StgAnsibleRunner ansible;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public StgPropertiesRunner(StgAnsibleRunner ansible) {
        this.ansible = ansible;
    }

    public static String cfgFile(String app) { return "/home/" + app + "/cfg/application.properties"; }
    public static String backupFile(String app) { return cfgFile(app) + ".bkp.ansible"; }
    public String blockFilePath(String app) { return "/tmp/props-block-stg-" + app + ".txt"; }

    public static List<String> blockLines(String block) {
        if (block == null || block.isBlank()) return List.of();
        String[] parts = block.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>(List.of(parts));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    public String command(String host, StgPropertiesRequest r, String blockPath) {
        StringBuilder sb = new StringBuilder("./properties.sh ")
                .append(host).append(' ').append(r.app()).append(' ').append(r.op());
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
            default -> { /* diff | restore */ }
        }
        if (r.testMode()) sb.append(" --test");
        return sb.toString();
    }

    // ---- demo mode (scripted output) --------------------------------------------------------

    public List<StgPropTermLine> script(String host, StgPropertiesRequest r, String blockPath, boolean changed) {
        String app = r.app(), op = r.op();
        boolean test = r.testMode();
        String file = cfgFile(app);
        List<StgPropTermLine> out = new ArrayList<>();

        out.add(line("user", "$ " + command(host, r, blockPath)));
        out.add(line("dim", "============================================"));
        out.add(line(test ? "task" : "ink", test ? " PROPERTIES EDIT — STAGING — TEST MODE (real file NOT modified)" : " PROPERTIES EDIT — STAGING"));
        out.add(line("dim", " Host     : " + host));
        out.add(line("dim", " App      : " + app));
        out.add(line("dim", " Operation: " + op));
        out.add(line("dim", " File     : " + file));
        if (test) out.add(line("dim", " Test file: " + file + ".test.ansible"));
        out.add(line("dim", "============================================"));

        List<String> block = blockLines(r.block());
        String stamp = STAMP.format(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());

        out.add(line("ink", stars("PLAY [properties — " + host + "]")));
        out.add(line("task", stars("TASK [GUARD — app / op / file exist]")));
        out.add(line("ok", "ok: [" + host + "]"));

        int h = hash(host + app);
        switch (op) {
            case "preview" -> {
                if (notBlank(r.key())) {
                    out.add(line("task", stars("TASK [preview — grep \"" + r.key() + "\"]")));
                    out.add(line("ink", "  " + (40 + h % 90) + ": " + r.key() + "=" + (h % 500)));
                } else {
                    out.add(line("task", stars("TASK [preview — full file with line numbers]")));
                    out.add(line("ink", "  1: server.port=" + (10030 + h % 40)));
                    out.add(line("ink", "  2: spring.application.name=" + app));
                    out.add(line("dim", "  … (" + (60 + h % 120) + " lines total)"));
                }
            }
            case "update" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [update — replace exact line]")));
                out.add(line("ch", "changed: [" + host + "] => 1 line replaced" + (test ? " (test copy)" : "")));
                diff(out, List.of(r.oldLine()), List.of(r.newLine()));
            }
            case "add_csv" -> {
                backup(out, host, app, test);
                String before = (h % 90) + "," + ((h >> 3) % 90);
                out.add(line("task", stars("TASK [add_csv — append to comma value]")));
                out.add(line("ch", "changed: [" + host + "] => " + r.key()));
                diff(out, List.of(r.key() + "=" + before), List.of(r.key() + "=" + before + "," + safe(r.values())));
            }
            case "remove_csv" -> {
                backup(out, host, app, test);
                String before = safe(r.values()) + "," + (h % 90) + "," + ((h >> 3) % 90);
                out.add(line("task", stars("TASK [remove_csv — drop from comma value]")));
                out.add(line("ch", "changed: [" + host + "] => " + r.key()));
                diff(out, List.of(r.key() + "=" + before), List.of(r.key() + "=" + (h % 90) + "," + ((h >> 3) % 90)));
            }
            case "rename" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [rename — key only, value kept]")));
                out.add(line("ch", "changed: [" + host + "] => key renamed"));
                diff(out, List.of(r.oldKey() + "=" + (h % 1000)), List.of(r.newKey() + "=" + (h % 1000)));
            }
            case "append" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [append — add block at end (date header)]")));
                out.add(line("ch", "changed: [" + host + "] => " + (block.size() + 1) + " line(s) appended"));
                List<String> added = new ArrayList<>();
                added.add("");
                added.add("##########" + stamp + "##########");
                added.addAll(block);
                diff(out, List.of(), added);
            }
            case "insert" -> {
                backup(out, host, app, test);
                out.add(line("task", stars("TASK [insert — add block after matched line]")));
                out.add(line("ch", "changed: [" + host + "] => " + block.size() + " line(s) inserted"));
                out.add(line("dim", "  after: " + r.afterLine()));
                diff(out, List.of(), block);
            }
            case "diff" -> {
                out.add(line("task", stars("TASK [diff — current vs .bkp.ansible]")));
                out.add(line("ok", "ok: [" + host + "] => " + (h % 2 == 0 ? "no differences from last backup" : "differences found (see report)")));
            }
            case "restore" -> {
                out.add(line("task", stars("TASK [restore — copy .bkp.ansible over live file]")));
                out.add(line("ch", "changed: [" + host + "] => restored " + file + " from " + backupFile(app)));
            }
            default -> { }
        }

        out.add(line("ink", stars("PLAY RECAP")));
        out.add(line("ok", pad(host) + ": ok=2  changed=" + (changed ? 1 : 0) + "  unreachable=0  failed=0"));
        if (test) out.add(line("task", "TEST MODE — real file was NOT modified. Review the diff, then re-run without --test."));
        return out;
    }

    private void backup(List<StgPropTermLine> out, String host, String app, boolean test) {
        if (test) {
            out.add(line("task", stars("TASK [test — copy real file to .test.ansible]")));
            out.add(line("ok", "ok: [" + host + "] => editing " + cfgFile(app) + ".test.ansible"));
        } else {
            out.add(line("task", stars("TASK [backup — .bkp.ansible before write]")));
            out.add(line("ch", "changed: [" + host + "] => " + backupFile(app)));
        }
    }

    private void diff(List<StgPropTermLine> out, List<String> removed, List<String> added) {
        for (String s : removed) out.add(line("del", "- " + s));
        for (String s : added) out.add(line("add", "+ " + s));
    }

    // ---- real execution ---------------------------------------------------------------------

    public List<StgPropTermLine> execute(String host, StgPropertiesRequest r) throws IOException, InterruptedException {
        String wd = shq(ansible.stgDir());
        List<StgPropTermLine> out = new ArrayList<>();

        // Block ops (append/insert) pass their pasted lines inline as single-quoted args — no
        // temp file is staged or left on the server. properties.yml backs up the live file first.
        String cmd = command(host, r, null);
        out.add(line("user", "$ " + cmd));
        String raw = ansible.capture("cd " + wd + " && " + cmd, 120);
        for (String l : raw.split("\n", -1)) out.add(classify(l));
        return out;
    }

    private static StgPropTermLine classify(String t) {
        if (t == null) return line("ink", "");
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

    private static StgPropTermLine line(String level, String text) { return new StgPropTermLine(level, text); }

    private static int hash(String s) {
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) { h ^= (s.charAt(i) & 0xff); h = (h * 16777619L) & 0xffffffffL; }
        return (int) (h % 100000);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String dq(String s) { return "\"" + safe(s) + "\""; }
    private static String shq(String s) { return "'" + safe(s).replace("'", "'\\''") + "'"; }
    private static String stars(String t) { return t + " " + "*".repeat(Math.max(6, 66 - t.length())); }
    private static String pad(String h) { return (h + "                    ").substring(0, 20); }
}
