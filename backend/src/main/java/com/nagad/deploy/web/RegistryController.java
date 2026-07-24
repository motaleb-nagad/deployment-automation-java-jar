package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.service.RegistryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RegistryController {

    private final RegistryService registry;

    public RegistryController(RegistryService registry) {
        this.registry = registry;
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
}
