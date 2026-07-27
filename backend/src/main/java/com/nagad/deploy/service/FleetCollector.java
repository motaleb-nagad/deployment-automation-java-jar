package com.nagad.deploy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real fleet collector. Shells out (over SSH to the jump host) to a single Ansible sweep that
 * reports, per inventory host, whether it is reachable and which service users have a running
 * jar under {@code /home/<user>/was/}. This is the same connectivity mechanism the operators
 * use ({@code ansible -m ping} / an ad-hoc {@code ps}), so the dashboard reflects reality
 * instead of the scripted demo fixtures.
 *
 * <p>Reachability and running/stopped are read without privilege escalation ({@code ps} shows
 * every user's command line). Jar hash / drift needs {@code become_user} to unzip
 * {@code git.properties}, so it is intentionally left out of this pass.
 *
 * <p>Snapshots are cached for a short interval and the collector never throws: on any failure
 * it returns a snapshot with {@code ok=false} so the view degrades to "unknown" rather than
 * breaking or inventing data.
 */
@Service
public class FleetCollector {

    private static final Logger log = LoggerFactory.getLogger(FleetCollector.class);

    /** One reachable host's parsed state. */
    public record Snapshot(boolean ok, Instant at, Map<String, Set<String>> running, Set<String> unreachable) {
        public boolean reachable(String host) { return running.containsKey(host); }
        public Set<String> runningApps(String host) { return running.getOrDefault(host, Set.of()); }
    }

    // Single sweep: per host, print a comma list of service users that have a running jar.
    // The mid-pipeline grep may exit non-zero when a host runs nothing; paste (the last stage)
    // still exits 0, so Ansible reports the host reachable with an empty list rather than FAILED.
    private static final String PS_SWEEP =
            "ps -eo args= | grep -oE '/home/[^/]+/was/[^ ]+\\.jar' | sed -E 's#.*/home/##; s#/was/.*##' | sort -u | paste -sd, -";

    private static final Pattern LINE = Pattern.compile("^(\\S+)\\s*\\|\\s*(\\S+)");
    private static final Duration TTL = Duration.ofSeconds(60);

    private final AnsibleRunner runner;

    private volatile Snapshot cached;

    public FleetCollector(AnsibleRunner runner) {
        this.runner = runner;
    }

    /** Cached snapshot, refreshed at most once per {@link #TTL}. */
    public synchronized Snapshot snapshot() {
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(TTL) < 0) {
            return cached;
        }
        cached = collect();
        return cached;
    }

    private Snapshot collect() {
        String cmd = "cd '" + runner.workingDir().replace("'", "'\\''") + "' && "
                + "ansible all -m shell -a \"" + PS_SWEEP + "\" -o 2>&1";
        String out;
        try {
            out = runner.capture(cmd, 45);
        } catch (Exception e) {
            log.warn("fleet collector sweep failed: {}", e.toString());
            return new Snapshot(false, Instant.now(), Map.of(), Set.of());
        }

        Map<String, Set<String>> running = new HashMap<>();
        Set<String> unreachable = new HashSet<>();
        for (String raw : out.split("\n")) {
            if (raw.isBlank()) continue;
            Matcher m = LINE.matcher(raw);
            if (!m.find()) continue;
            String host = strip(m.group(1));
            String verdict = m.group(2);
            if (raw.contains("UNREACHABLE") || "FAILED".equals(verdict)) {
                unreachable.add(host);
                continue;
            }
            int i = raw.indexOf(">>");
            String csv = i >= 0 ? raw.substring(i + 2).trim() : "";
            Set<String> apps = new HashSet<>();
            for (String a : csv.split(",")) {
                a = a.trim();
                if (!a.isEmpty()) apps.add(a);
            }
            running.put(host, apps);
        }

        if (running.isEmpty() && unreachable.isEmpty()) {
            log.warn("fleet collector parsed no hosts from sweep output");
            return new Snapshot(false, Instant.now(), Map.of(), Set.of());
        }
        return new Snapshot(true, Instant.now(), running, unreachable);
    }

    /** Inventory names are prefixed (nagad-app1); the fleet model uses the short name (app1). */
    private static String strip(String host) {
        return host.startsWith("nagad-") ? host.substring("nagad-".length()) : host;
    }
}
