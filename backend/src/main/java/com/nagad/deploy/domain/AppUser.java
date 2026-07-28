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

    /** True until the user replaces the admin-provisioned password on first sign-in. */
    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    /** Create a new account (password already hashed). */
    public AppUser(String username, String name, String email, Role role, String scope,
                   boolean permR, boolean permW, boolean permX, String passwordHash,
                   boolean mustChangePassword) {
        this.username = username;
        this.name = name;
        this.email = email;
        this.role = role;
        this.scope = scope;
        this.permR = permR;
        this.permW = permW;
        this.permX = permX;
        this.passwordHash = passwordHash;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = Instant.now();
    }

    /** Set a new (already hashed) password and clear the first-login change requirement. */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
    }

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
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getCreatedAt() { return createdAt; }

    public String perms() {
        return (permR ? "r" : "·") + (permW ? "w" : "·") + (permX ? "x" : "·");
    }
}
