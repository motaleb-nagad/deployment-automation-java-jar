package com.nagad.deploy.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Current deployed truth per app+group. */
@Entity
@Table(name = "jar_registry")
@IdClass(JarRegistry.Key.class)
public class JarRegistry {

    @Id
    private String app;

    @Id
    @Column(name = "group_name")
    private String groupName;

    private String jar;

    @Column(name = "prod_hash")
    private String prodHash;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private String updatedBy;

    protected JarRegistry() {}

    public JarRegistry(String app, String groupName, String jar, String prodHash, String updatedBy) {
        this.app = app;
        this.groupName = groupName;
        this.jar = jar;
        this.prodHash = prodHash;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public void updateHash(String hash, String by) {
        this.prodHash = hash;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public String getApp() { return app; }
    public String getGroupName() { return groupName; }
    public String getJar() { return jar; }
    public String getProdHash() { return prodHash; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    public static class Key implements Serializable {
        private String app;
        private String groupName;
        public Key() {}
        public Key(String app, String groupName) { this.app = app; this.groupName = groupName; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(app, k.app) && Objects.equals(groupName, k.groupName);
        }
        @Override public int hashCode() { return Objects.hash(app, groupName); }
    }
}
