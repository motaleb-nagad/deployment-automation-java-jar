package com.nagad.deploy.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only. Never updated or deleted (Postgres enforces this with a trigger). */
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
    public Instant getTs() { return ts; }
    public String getActor() { return actor; }
    public String getVerb() { return verb; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
}
