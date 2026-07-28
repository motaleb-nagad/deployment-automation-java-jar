package com.nagad.deploy.service;

import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.repo.JarRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for "what production runs right now" for a given app in a group.
 * If a deploy has recorded a hash in the jar registry, that is production's current truth;
 * otherwise fall back to the live fleet-inventory hash (the collector stand-in). Used by the
 * promote, deploy-review and registry read models so every screen agrees on the prod hash.
 */
@Service
public class ProdHashService {

    private final FleetInventory inv;
    private final JarRegistryRepository registry;

    public ProdHashService(FleetInventory inv, JarRegistryRepository registry) {
        this.inv = inv;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public String current(String group, String app) {
        return registry.findById(new JarRegistry.Key(app, group))
                .map(JarRegistry::getProdHash)
                .filter(h -> h != null && !h.isBlank())
                .orElseGet(() -> inv.hash(group, app));
    }
}
