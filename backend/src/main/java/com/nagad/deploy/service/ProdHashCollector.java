package com.nagad.deploy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the <em>real</em> git commit hash of the jar currently deployed on production, per app,
 * by running {@code check-hash.yml} on the jump host — the same playbook the ops team uses. Its
 * output lines look like {@code nagad-app1 --> spg ---> 0fefb71}; we parse those into an
 * {@code app -> hash} map so the console's PROD HASH matches what the servers actually run.
 *
 * <p>Only consulted in real mode. Reads are per wrapper-group (one playbook run returns every
 * app on that group's hosts) and cached with a TTL so opening a screen can't hammer prod. The
 * collector never throws: on any failure (unreachable, sudo prompt, parse miss) it returns an
 * empty result and the caller falls back to its previous prod-hash source.
 */
@Service
public class ProdHashCollector {

    private static final Logger log = LoggerFactory.getLogger(ProdHashCollector.class);

    private final AnsibleRunner runner;
    private final Duration ttl;
    private final int timeoutSeconds;

    // cmd (wrapper group) -> the inventory group check-hash.yml has a play for.
    private static final Map<String, String> INV_GROUP = Map.of(
            "core", "nagad-app", "web", "nagad-web", "web-dmz", "nagad-web-dmz", "ussd", "nagad-ussd",
            "npsb-apigw", "nagad-npsb-apigw", "npsb-spg", "nagad-npsb-spg",
            "npsb-pp", "nagad-npsb-pp", "npsb-png", "nagad-npsb-png");

    // "nagad-app1 --> spg ---> 0fefb71"
    private static final Pattern LINE = Pattern.compile("[\\w.-]+ --> (\\S+)\\s*--->\\s*(\\S+)");

    private record Snap(Instant at, Map<String, String> appHash) {}
    private final Map<String, Snap> cache = new ConcurrentHashMap<>();

    public ProdHashCollector(AnsibleRunner runner,
                             @Value("${nagad.ansible.prodhash.ttl-seconds:180}") long ttlSeconds,
                             @Value("${nagad.ansible.prodhash.timeout-seconds:90}") int timeoutSeconds) {
        this.runner = runner;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The live prod hash for an app in a wrapper group, or empty if it can't be read. */
    public Optional<String> hashFor(String cmdGroup, String app) {
        if (cmdGroup == null || !INV_GROUP.containsKey(cmdGroup)) return Optional.empty();
        Snap snap = snapshot(cmdGroup);
        String h = snap.appHash().get(app);
        return (h == null || h.isBlank() || "NOT FOUND".equals(h)) ? Optional.empty() : Optional.of(h);
    }

    private synchronized Snap snapshot(String cmdGroup) {
        Snap existing = cache.get(cmdGroup);
        if (existing != null && Duration.between(existing.at(), Instant.now()).compareTo(ttl) < 0) {
            return existing;
        }
        Snap fresh = new Snap(Instant.now(), collect(cmdGroup));
        cache.put(cmdGroup, fresh);
        return fresh;
    }

    private Map<String, String> collect(String cmdGroup) {
        String invGroup = INV_GROUP.get(cmdGroup);
        String cmd = "cd '" + runner.workingDir().replace("'", "'\\''") + "'"
                + " && ansible-playbook check-hash.yml -l " + invGroup + " -e 'apps=all' 2>&1";
        Map<String, String> out = new HashMap<>();
        try {
            String raw = runner.capture(cmd, timeoutSeconds);
            Matcher m = LINE.matcher(raw == null ? "" : raw);
            while (m.find()) {
                // first host wins for a given app (all hosts in a group should agree)
                out.putIfAbsent(m.group(1), m.group(2).trim());
            }
            if (out.isEmpty()) log.warn("check-hash for {} returned no parseable hash lines", cmdGroup);
        } catch (Exception e) {
            log.warn("prod hash read for {} failed: {}", cmdGroup, e.toString());
        }
        return out;
    }
}
