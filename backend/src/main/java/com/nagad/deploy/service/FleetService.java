package com.nagad.deploy.service;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.service.FleetInventory.Group;
import com.nagad.deploy.service.FleetInventory.Svc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds the fleet dashboard view. Supports two demo scenarios — {@code incident} (two
 * services down, one group in hash drift, one restart, one unknown) and {@code healthy}
 * (all clear) — so the "calm all-healthy" and "something is wrong" states from the brief
 * are both demonstrable.
 */
@Service
public class FleetService {

    private final FleetInventory inv;
    private final FleetCollector collector;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String NA = "—";

    // Incident fixtures.
    private record Down(String g, String h, String s, String since) {}
    private static final List<Down> DOWNS = List.of(
            new Down("nagad-app", "app3", "npsb_recon", "22 min"),
            new Down("nagad-ussd", "ussd4", "ussdgwgp", "9 min"));
    private static final Down UNKNOWN = new Down("nagad-knotifypush", "knotifypush2", "knotifypush", "");
    private static final String DRIFT_GROUP = "nagad-app", DRIFT_SVC = "apigw", DRIFT_OLD = "3ba90c2";
    private static final Set<String> DRIFT_HOSTS = Set.of("app5", "app6");
    private static final String RESTART_GROUP = "nagad-app", RESTART_HOST = "app2", RESTART_SVC = "map", RESTART_UP = "0d 00:41";

    public FleetService(FleetInventory inv, FleetCollector collector) {
        this.inv = inv;
        this.collector = collector;
    }

    public FleetView view(String scenario) {
        // Demo mode keeps the scripted incident/healthy fixtures; production reads the real
        // collector so the board shows actual reachability + running/stopped state.
        if (!simulate) return realView();
        boolean incident = !"healthy".equalsIgnoreCase(scenario);

        int hostsTotal = 0, instancesTotal = 0;
        Set<String> svcKeys = new HashSet<>();
        List<GroupView> groupViews = new ArrayList<>();

        for (Group g : inv.groups()) {
            hostsTotal += g.hosts().size();
            for (Svc s : g.svcs()) {
                svcKeys.add(s.key());
                instancesTotal += s.instances() * g.hosts().size();
            }

            List<ServiceCellView> cells = new ArrayList<>();
            for (Svc s : g.svcs()) {
                String cur = inv.hash(g.key(), s.key());
                for (var host : g.hosts()) {
                    String status = statusOf(incident, g.key(), host.name(), s.key());
                    String hash = hashOf(incident, g.key(), host.name(), s.key(), cur);
                    String up = uptimeOf(incident, g.key(), host.name(), s.key());
                    cells.add(new ServiceCellView(s.key(), host.name(), status, hash, up,
                            String.valueOf(inv.pid(g.key() + host.name() + s.key())),
                            host.ip(), s.jar(), s.instances(), lastDeployed(g.key(), s.key())));
                }
            }
            groupViews.add(new GroupView(g.key(), g.cmd(), g.zone(), g.tier(),
                    g.hosts().stream().map(FleetInventory.Host::name).toList(),
                    cells, issues(incident, g.key())));
        }

        List<AttentionView> attention = new ArrayList<>();
        if (incident) {
            for (Down d : DOWNS) {
                attention.add(new AttentionView("DOWN", d.s(), d.g() + " · " + d.h(),
                        "stopped " + d.since() + " ago · expected running · pid gone", d.g(), d.s(), d.h()));
            }
            attention.add(new AttentionView("DRIFT", DRIFT_SVC, DRIFT_GROUP + " · app5, app6",
                    "app5–6 on " + DRIFT_OLD + ", app1–4 on " + inv.hash(DRIFT_GROUP, DRIFT_SVC)
                            + " — matches failed deploy 22 Jul 23:30 (m.hasan)", DRIFT_GROUP, DRIFT_SVC, "app5"));
            attention.add(new AttentionView("RESTART", RESTART_SVC, RESTART_GROUP + " · " + RESTART_HOST,
                    "uptime " + RESTART_UP + " · peers run 9d+ · restarted outside a deploy window",
                    RESTART_GROUP, RESTART_SVC, RESTART_HOST));
            attention.add(new AttentionView("UNKNOWN", UNKNOWN.s(), UNKNOWN.g() + " · " + UNKNOWN.h(),
                    "collector could not read status · last host contact 14:29:41", UNKNOWN.g(), UNKNOWN.s(), UNKNOWN.h()));
        }

        int servicesDown = incident ? DOWNS.size() : 0;
        int unknowns = incident ? 1 : 0;
        int driftGroups = incident ? 1 : 0;
        int restarts = incident ? 1 : 0;

        String collectedAt = LocalTime.of(14, 32, 6).format(HMS);
        return new FleetView(collectedAt, "3 min ago", incident,
                hostsTotal, svcKeys.size(), instancesTotal,
                servicesDown, driftGroups, restarts, unknowns, attention, groupViews);
    }

    /**
     * Real dashboard built from the collector snapshot. Reachability + running/stopped are
     * measured; jar hash / uptime aren't read in this pass, so they show as "—". If the sweep
     * failed, every service is reported "unknown" (never faked).
     */
    private FleetView realView() {
        FleetCollector.Snapshot snap = collector.snapshot();

        int hostsTotal = 0, instancesTotal = 0, servicesDown = 0, unknowns = 0;
        Set<String> svcKeys = new HashSet<>();
        List<GroupView> groupViews = new ArrayList<>();
        List<AttentionView> attention = new ArrayList<>();

        for (Group g : inv.groups()) {
            hostsTotal += g.hosts().size();
            List<ServiceCellView> cells = new ArrayList<>();
            List<String> issues = new ArrayList<>();

            for (Svc s : g.svcs()) {
                svcKeys.add(s.key());
                instancesTotal += s.instances() * g.hosts().size();
                for (var host : g.hosts()) {
                    String status;
                    if (!snap.ok() || snap.unreachable().contains(host.name())) {
                        status = "unknown";
                    } else {
                        status = snap.runningApps(host.name()).contains(s.key()) ? "running" : "stopped";
                    }
                    if ("stopped".equals(status)) {
                        servicesDown++;
                        if (!issues.contains("down")) issues.add("down");
                        if (attention.size() < 40) {
                            attention.add(new AttentionView("DOWN", s.key(), g.key() + " · " + host.name(),
                                    "no running jar under /home/" + s.key() + "/was · expected running",
                                    g.key(), s.key(), host.name()));
                        }
                    } else if ("unknown".equals(status)) {
                        unknowns++;
                        if (!issues.contains("unknown")) issues.add("unknown");
                    }
                    cells.add(new ServiceCellView(s.key(), host.name(), status, NA, NA, NA,
                            host.ip(), s.jar(), s.instances(), NA));
                }
            }
            groupViews.add(new GroupView(g.key(), g.cmd(), g.zone(), g.tier(),
                    g.hosts().stream().map(FleetInventory.Host::name).toList(), cells, issues));
        }

        if (!snap.ok()) {
            attention.add(0, new AttentionView("UNKNOWN", "collector", "fleet",
                    "collector could not reach the jump host — every service shown as unknown",
                    "", "", ""));
        } else {
            for (String h : snap.unreachable()) {
                attention.add(new AttentionView("UNKNOWN", "host", h,
                        "host unreachable over ansible ping — SSH/port/auth", "", "", h));
            }
        }

        boolean incident = servicesDown > 0 || unknowns > 0 || !snap.ok();
        String collectedAt = LocalTime.ofInstant(snap.at(), ZoneId.systemDefault()).format(HMS);
        String age = humanAge(snap.at());
        return new FleetView(collectedAt, age, incident,
                hostsTotal, svcKeys.size(), instancesTotal,
                servicesDown, 0, 0, unknowns, attention, groupViews);
    }

    private static String humanAge(Instant at) {
        long secs = Math.max(0, Duration.between(at, Instant.now()).getSeconds());
        if (secs < 60) return secs + " sec ago";
        return (secs / 60) + " min ago";
    }

    private String statusOf(boolean incident, String g, String h, String s) {
        if (incident) {
            if (DOWNS.stream().anyMatch(d -> d.g().equals(g) && d.h().equals(h) && d.s().equals(s))) return "stopped";
            if (UNKNOWN.g().equals(g) && UNKNOWN.h().equals(h) && UNKNOWN.s().equals(s)) return "unknown";
        }
        return "running";
    }

    private String hashOf(boolean incident, String g, String h, String s, String cur) {
        if (incident && DRIFT_GROUP.equals(g) && DRIFT_SVC.equals(s) && DRIFT_HOSTS.contains(h)) return DRIFT_OLD;
        return cur;
    }

    private String uptimeOf(boolean incident, String g, String h, String s) {
        if (incident && RESTART_GROUP.equals(g) && RESTART_HOST.equals(h) && RESTART_SVC.equals(s)) return RESTART_UP;
        return inv.uptime(g, h, s);
    }

    private String lastDeployed(String g, String s) {
        String[] opts = {"19 Jun 2026 02:14", "02 Jun 2026 03:40", "18 May 2026 21:02", "11 Jul 2026 01:05"};
        if (DRIFT_GROUP.equals(g) && DRIFT_SVC.equals(s)) return "22 Jul 2026 22:58";
        return opts[(int) (inv.h32(g + s) % 4)];
    }

    private List<String> issues(boolean incident, String groupKey) {
        if (!incident) return List.of();
        List<String> out = new ArrayList<>();
        if (DOWNS.stream().anyMatch(d -> d.g().equals(groupKey))) out.add("down");
        if (DRIFT_GROUP.equals(groupKey)) out.add("drift");
        if (RESTART_GROUP.equals(groupKey)) out.add("restart");
        if (UNKNOWN.g().equals(groupKey)) out.add("unknown");
        return out;
    }
}
