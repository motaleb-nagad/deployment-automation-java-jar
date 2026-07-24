package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.repo.AuditLogRepository;
import com.nagad.deploy.repo.JarRegistryRepository;
import com.nagad.deploy.repo.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Read models for the Registry, Admin and Audit screens. */
@Service
public class RegistryService {

    private final JarRegistryRepository registry;
    private final PromotionRepository promos;
    private final AppUserRepository users;
    private final AuditLogRepository audit;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    public RegistryService(JarRegistryRepository registry, PromotionRepository promos,
                           AppUserRepository users, AuditLogRepository audit) {
        this.registry = registry;
        this.promos = promos;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RegistryRow> registryRows() {
        return registry.findAllByOrderByAppAsc().stream().map(r -> {
            Promotion latest = promos.findAllByOrderByRequestedAtDesc().stream()
                    .filter(p -> p.getApp().equals(r.getApp()) && p.getGroupName().equals(r.getGroupName()))
                    .findFirst().orElse(null);
            return new RegistryRow(r.getApp(), r.getGroupName(), r.getJar(), r.getProdHash(),
                    latest != null ? latest.getGitHash() : r.getProdHash(),
                    latest != null ? latest.getStatus().wire() : "deployed",
                    latest != null ? latest.getRequestedBy() : r.getUpdatedBy(),
                    FMT.format(r.getUpdatedAt()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<AdminRow> adminRows() {
        return users.findAll().stream()
                .map(u -> new AdminRow(u.getUsername(), u.getName(), u.getRole().wire(),
                        u.getScope(), u.isPermR(), u.isPermW(), u.isPermX()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditRow> auditRows() {
        return audit.findTop200ByOrderByTsDesc().stream()
                .map(a -> new AuditRow(FMT.format(a.getTs()), a.getActor(), a.getVerb(),
                        a.getTarget(), a.getDetail()))
                .toList();
    }
}
