package com.nagad.deploystg.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Deployment history row — the same {@code deployment} table the main console owns (schema
 * managed by the main backend's Flyway migrations). Staging runs are written here so they show
 * in the console's History tab alongside production runs.
 */
@Entity
@Table(name = "deployment")
public class Deployment {

    @Id
    private String id;

    @Column(name = "promotion_id")
    private String promotionId;

    @Column(name = "group_name")
    private String groupName;

    private String hosts;
    private String apps;
    private String actions;

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "started_at")
    private Instant startedAt = Instant.now();

    private String duration;
    private String result;

    @Column(name = "before_after", columnDefinition = "text")
    private String beforeAfter;

    @Column(name = "log_excerpt", columnDefinition = "text")
    private String logExcerpt;

    protected Deployment() {}

    public Deployment(String id, String promotionId, String groupName, String hosts, String apps,
                      String actions, String startedBy) {
        this.id = id;
        this.promotionId = promotionId;
        this.groupName = groupName;
        this.hosts = hosts;
        this.apps = apps;
        this.actions = actions;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
        this.result = "running";
    }

    public void complete(String result, String duration, String beforeAfter, String logExcerpt) {
        this.result = result;
        this.duration = duration;
        this.beforeAfter = beforeAfter;
        this.logExcerpt = logExcerpt;
    }

    public String getId() { return id; }
}
