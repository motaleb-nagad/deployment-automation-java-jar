package com.nagad.deploy.service;

import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.repo.JarRegistryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for "what production runs right now" for a given app in a group.
 * In real mode this is the actual git commit hash read off the deployed jar (via
 * {@link ProdHashCollector}, which runs {@code check-hash.yml}), so every screen shows what the
 * servers truly run. If that read isn't available (demo mode, unreachable, sudo) it falls back
 * to a hash a deploy recorded in the jar registry, and finally to the deterministic
 * fleet-inventory stand-in. Used by the promote, deploy-review and registry read models so
 * every screen agrees on the prod hash.
 */
@Service
public class ProdHashService {

    private final FleetInventory inv;
    private final JarRegistryRepository registry;
    private final ProdHashCollector collector;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    public ProdHashService(FleetInventory inv, JarRegistryRepository registry, ProdHashCollector collector) {
        this.inv = inv;
        this.registry = registry;
        this.collector = collector;
    }

    @Transactional(readOnly = true)
    public String current(String group, String app) {
        // Real, on-server truth takes precedence when we can read it.
        if (!simulate) {
            var live = collector.hashFor(group, app);
            if (live.isPresent()) return live.get();
        }
        return registry.findById(new JarRegistry.Key(app, group))
                .map(JarRegistry::getProdHash)
                .filter(h -> h != null && !h.isBlank())
                .orElseGet(() -> inv.hash(group, app));
    }

    /** Like {@link #current} but forces a fresh read of the live jar (drops the cache first) —
     *  used for the "after" hash right after a deploy so it reflects what was just installed. */
    @Transactional(readOnly = true)
    public String refreshed(String group, String app) {
        if (!simulate) {
            collector.invalidate(group);
            var live = collector.hashFor(group, app);
            if (live.isPresent()) return live.get();
        }
        return current(group, app);
    }
}
