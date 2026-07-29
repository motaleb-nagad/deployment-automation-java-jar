package com.nagad.deploystg.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only compliance trail — the same {@code audit_log} table the main console owns. A
 * Postgres trigger (installed by the main backend's migrations) blocks UPDATE/DELETE; this
 * service only ever inserts.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant ts = Instant.now();
    private String actor;
    private String verb;
    private String target;
    private String detail;

    protected AuditLog() {}

    public AuditLog(String actor, String verb, String target, String detail) {
        this.ts = Instant.now();
        this.actor = actor;
        this.verb = verb;
        this.target = target;
        this.detail = detail;
    }

    public Long getId() { return id; }
}
