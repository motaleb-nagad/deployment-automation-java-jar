package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.RegistryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RegistryController {

    private final RegistryService registry;
    private final CurrentUser current;

    public RegistryController(RegistryService registry, CurrentUser current) {
        this.registry = registry;
        this.current = current;
    }

    @GetMapping("/registry")
    public List<RegistryRow> registry() {
        return registry.registryRows();
    }

    @GetMapping("/audit")
    public List<AuditRow> audit() {
        return registry.auditRows();
    }

    /** Access control screen — super-admin only. */
    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<AdminRow> users() {
        return registry.adminRows();
    }

    /** Provision a new account — super-admin only. */
    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<AdminRow> createUser(@RequestBody CreateUserRequest req) {
        registry.createUser(current.require().getUsername(), req);
        return registry.adminRows();
    }

    /** Remove an account — super-admin only. */
    @DeleteMapping("/admin/users/{username}")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public List<AdminRow> deleteUser(@PathVariable String username) {
        registry.deleteUser(current.require().getUsername(), username);
        return registry.adminRows();
    }
}
