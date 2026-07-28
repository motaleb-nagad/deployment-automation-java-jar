package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.Role;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.repo.AuditLogRepository;
import com.nagad.deploy.repo.JarRegistryRepository;
import com.nagad.deploy.repo.PromotionRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Read models for the Registry, Admin and Audit screens. */
@Service
public class RegistryService {

    private final JarRegistryRepository registry;
    private final PromotionRepository promos;
    private final AppUserRepository users;
    private final AuditLogRepository audit;
    private final PasswordEncoder encoder;
    private final AuditService auditService;
    private final ProdHashService prodHash;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    public RegistryService(JarRegistryRepository registry, PromotionRepository promos,
                           AppUserRepository users, AuditLogRepository audit,
                           PasswordEncoder encoder, AuditService auditService, ProdHashService prodHash) {
        this.registry = registry;
        this.promos = promos;
        this.users = users;
        this.audit = audit;
        this.encoder = encoder;
        this.auditService = auditService;
        this.prodHash = prodHash;
    }

    /** Super-admin provisions a new account with an initial (hashed) password. */
    @Transactional
    public void createUser(String actor, CreateUserRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (username.isEmpty() || req.password() == null || req.password().length() < 6) {
            throw new ResponseStatusException(BAD_REQUEST, "username required and password must be at least 6 characters");
        }
        if (users.existsById(username)) {
            throw new ResponseStatusException(CONFLICT, "user " + username + " already exists");
        }
        String scope = (req.scope() == null || req.scope().isBlank()) ? "all" : req.scope().trim();
        // The admin-set password is single-use: the account must change it on first sign-in,
        // so the super admin never knows the password the user ends up with.
        AppUser u = new AppUser(username, req.name() == null ? username : req.name(),
                req.email() == null ? username : req.email(), Role.of(req.role() == null ? "viewer" : req.role()),
                scope, req.r(), req.w(), req.x(), encoder.encode(req.password()), true);
        users.save(u);
        auditService.record(actor, "user-create", username,
                "created " + Role.of(req.role() == null ? "viewer" : req.role()).wire() + " " + u.perms() + " scope=" + scope);
    }

    /** Remove an account. The last remaining super-admin cannot be deleted. */
    @Transactional
    public void deleteUser(String actor, String username) {
        AppUser u = users.findById(username)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "no such user " + username));
        if (u.getRole() == Role.SUPERADMIN && users.findByRole(Role.SUPERADMIN).size() <= 1) {
            throw new ResponseStatusException(CONFLICT, "cannot delete the last super admin");
        }
        users.deleteById(username);
        auditService.record(actor, "user-delete", username, "removed account");
    }

    /**
     * The registry lists every governed jar — one that has been fetched from staging and/or
     * deployed to production — showing the hash production runs right now beside its latest
     * promotion. No fabricated rows: an app appears only once it has real promotion or
     * deployment history, and the prod hash is the live production truth.
     */
    @Transactional(readOnly = true)
    public List<RegistryRow> registryRows() {
        // Distinct (app, group) keys drawn from real history — promotions and deploy records.
        java.util.Map<String, String[]> keys = new java.util.LinkedHashMap<>(); // key -> [app, group]
        for (JarRegistry r : registry.findAllByOrderByAppAsc()) {
            keys.putIfAbsent(r.getApp() + "|" + r.getGroupName(), new String[]{r.getApp(), r.getGroupName()});
        }
        for (Promotion p : promos.findAllByOrderByRequestedAtDesc()) {
            keys.putIfAbsent(p.getApp() + "|" + p.getGroupName(), new String[]{p.getApp(), p.getGroupName()});
        }

        List<Promotion> allPromos = promos.findAllByOrderByRequestedAtDesc();
        return keys.values().stream()
                .sorted(java.util.Comparator.comparing((String[] k) -> k[0]).thenComparing(k -> k[1]))
                .map(k -> {
                    String app = k[0], group = k[1];
                    Promotion latest = allPromos.stream()
                            .filter(p -> p.getApp().equals(app) && p.getGroupName().equals(group))
                            .findFirst().orElse(null);
                    JarRegistry r = registry.findById(new JarRegistry.Key(app, group)).orElse(null);
                    String prod = prodHash.current(group, app);
                    String jar = r != null ? r.getJar()
                            : latest != null ? latest.getJar()
                            : FleetInventory.JAR_MAP.getOrDefault(app, app + "-1.0.jar");
                    String latestHash = latest != null ? latest.getGitHash() : prod;
                    String latestStatus = latest != null ? latest.getStatus().wire() : "deployed";
                    String latestBy = latest != null ? latest.getRequestedBy()
                            : r != null ? r.getUpdatedBy() : "—";
                    String updatedAt = r != null ? FMT.format(r.getUpdatedAt())
                            : latest != null ? PromotionService.fmt(latest.getRequestedAt()) : "—";
                    return new RegistryRow(app, group, jar, prod, latestHash, latestStatus, latestBy, updatedAt);
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
