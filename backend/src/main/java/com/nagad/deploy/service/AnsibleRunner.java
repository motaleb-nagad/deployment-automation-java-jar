package com.nagad.deploy.service;

import com.nagad.deploy.service.FleetInventory.Group;
import com.nagad.deploy.service.FleetInventory.Svc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the live console stream for a run. In demo mode ({@code nagad.ansible.simulate})
 * it builds scripted Ansible output line-by-line, exactly what the wrapper prints; in
 * production a worker would instead {@code ProcessBuilder} the real {@code ./run.sh} and
 * forward its stdout through the same {@link Line} channel.
 */
@Component
public class AnsibleRunner {

    /** level: user | dim | ink | task | ok | ch | fatal. rail fields drive the per-host tracker. */
    public record Line(String level, String text, String railHost, String railAction, String railState) {
        static Line log(String level, String text) { return new Line(level, text, null, null, null); }
        static Line host(String level, String text, String host, String action, String state) {
            return new Line(level, text, host, action, state);
        }
    }

    public String command(String cmd, List<String> hosts, List<String> apps, List<String> actions) {
        return cmd + " " + hostExpr(hosts) + " " + String.join(",", apps) + " " + String.join(",", actions) + " -K";
    }

    public static String hostExpr(List<String> hosts) {
        if (hosts.isEmpty()) return "all";
        if (hosts.size() == 1) return hosts.get(0);
        return hosts.get(0) + ".." + hosts.get(hosts.size() - 1);
    }

    public List<Line> script(Group g, List<String> hosts, List<String> apps, List<String> actions,
                             FleetInventory inv, String cmd) {
        List<Line> out = new ArrayList<>();
        out.add(Line.log("user", "$ " + cmd));
        out.add(Line.log("dim", "BECOME password: ********"));
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

    private static String stars(String t) {
        return t + " " + "*".repeat(Math.max(6, 66 - t.length()));
    }

    private static String pad(String h) {
        return (h + "            ").substring(0, 12);
    }
}
