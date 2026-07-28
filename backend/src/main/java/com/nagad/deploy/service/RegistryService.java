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

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    public RegistryService(JarRegistryRepository registry, PromotionRepository promos,
                           AppUserRepository users, AuditLogRepository audit,
                           PasswordEncoder encoder, AuditService auditService) {
        this.registry = registry;
        this.promos = promos;
        this.users = users;
        this.audit = audit;
        this.encoder = encoder;
        this.auditService = auditService;
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
