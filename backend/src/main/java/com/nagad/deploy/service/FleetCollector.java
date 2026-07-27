package com.nagad.deploy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Real fleet collector. The portal points at production, so it never invents its own Ansible
 * commands: it calls a status script that lives in the ansible package ({@code fleet-status.sh}
 * in the wrapper's working dir) and parses that script's stable, tab-separated output. All
 * logic for HOW status is gathered — reachability, running-jar detection, forks/timeout to
 * bound prod load — lives in that package script, the single source of truth. To change what
 * status means, change the script, not this class.
 *
 * <p>Collection is on-demand (only when the Fleet view is requested) and cached for a few
 * minutes, so opening the dashboard can't hammer prod. The collector never throws: on any
 * failure it returns {@code ok=false} and the view degrades to "unknown" rather than faking.
 *
 * <p>Expected script output, one line per host:
 * <pre>{@code <host>\t<UP|UNREACHABLE>\t<csv of running service users>}</pre>
 */
@Service
public class FleetCollector {

    private static final Logger log = LoggerFactory.getLogger(FleetCollector.class);

    public record Snapshot(boolean ok, Instant at, Map<String, Set<String>> running, Set<String> unreachable) {
        public boolean reachable(String host) { return running.containsKey(host); }
        public Set<String> runningApps(String host) { return running.getOrDefault(host, Set.of()); }
    }

    private final AnsibleRunner runner;
    private final String statusCommand;
    private final int timeoutSeconds;
    private final Duration ttl;

    private volatile Snapshot cached;

    public FleetCollector(
            AnsibleRunner runner,
            @Value("${nagad.ansible.fleet.command:./fleet-status.sh}") String statusCommand,
            @Value("${nagad.ansible.fleet.timeout-seconds:60}") int timeoutSeconds,
            @Value("${nagad.ansible.fleet.ttl-seconds:180}") long ttlSeconds) {
        this.runner = runner;
        this.statusCommand = statusCommand;
        this.timeoutSeconds = timeoutSeconds;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Cached snapshot, refreshed at most once per configured TTL. */
    public synchronized Snapshot snapshot() {
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(ttl) < 0) {
            return cached;
        }
        cached = collect();
        return cached;
    }

    private Snapshot collect() {
        // Run the package-owned status script in the wrapper's working dir. The backend adds
        // no ansible logic of its own — it just executes the script and reads its output.
        String cmd = "cd '" + runner.workingDir().replace("'", "'\\''") + "' && " + statusCommand;
        String out;
        try {
            out = runner.capture(cmd, timeoutSeconds);
        } catch (Exception e) {
            log.warn("fleet status script failed: {}", e.toString());
            return new Snapshot(false, Instant.now(), Map.of(), Set.of());
        }

        Map<String, Set<String>> running = new HashMap<>();
        Set<String> unreachable = new HashSet<>();
        for (String raw : out.split("\n")) {
            if (raw.isBlank()) continue;
            String[] f = raw.split("\t", -1);
            if (f.length < 2) continue;                 // not a status line (banner, error, …)
            String host = strip(f[0].trim());
            String status = f[1].trim();
            if ("UNREACHABLE".equals(status)) {
                unreachable.add(host);
                continue;
            }
            if (!"UP".equals(status)) continue;
            Set<String> apps = new HashSet<>();
            if (f.length >= 3) {
                for (String a : f[2].split(",")) {
                    a = a.trim();
                    if (!a.isEmpty()) apps.add(a);
                }
            }
            running.put(host, apps);
        }

        if (running.isEmpty() && unreachable.isEmpty()) {
            log.warn("fleet status script returned no parseable host lines");
            return new Snapshot(false, Instant.now(), Map.of(), Set.of());
        }
        return new Snapshot(true, Instant.now(), running, unreachable);
    }

    /** Inventory names are prefixed (nagad-app1); the fleet model uses the short name (app1). */
    private static String strip(String host) {
        return host.startsWith("nagad-") ? host.substring("nagad-".length()) : host;
    }
}
