package com.nagad.deploy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private String username;

    private String name;
    private String email;

    @Convert(converter = RoleConverter.class)
    private Role role;

    /** 'all' or a comma-separated list of wrapper groups (core, web, ussd, …). */
    private String scope;

    @Column(name = "perm_r")
    private boolean permR;
    @Column(name = "perm_w")
    private boolean permW;
    @Column(name = "perm_x")
    private boolean permX;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public boolean scopeAll() {
        return "all".equalsIgnoreCase(scope);
    }

    public List<String> scopeGroups() {
        return scopeAll() ? List.of() : Arrays.stream(scope.split(",")).map(String::trim).toList();
    }

    public boolean canAccessGroup(String wrapperGroup) {
        return scopeAll() || scopeGroups().contains(wrapperGroup);
    }

    // getters
    public String getUsername() { return username; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public String getScope() { return scope; }
    public boolean isPermR() { return permR; }
    public boolean isPermW() { return permW; }
    public boolean isPermX() { return permX; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }

    public String perms() {
        return (permR ? "r" : "·") + (permW ? "w" : "·") + (permX ? "x" : "·");
    }
}
