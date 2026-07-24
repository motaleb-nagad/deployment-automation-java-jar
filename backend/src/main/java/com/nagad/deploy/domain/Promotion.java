package com.nagad.deploy.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotion")
public class Promotion {

    @Id
    private String id;                 // PR-1042

    private String app;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "src_host")
    private String srcHost;

    private String jar;

    @Column(name = "git_hash")
    private String gitHash;

    @Column(name = "prev_hash")
    private String prevHash;

    private String branch;

    @Column(name = "size_label")
    private String sizeLabel;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt = Instant.now();

    @Convert(converter = PromotionStatusConverter.class)
    private PromotionStatus status = PromotionStatus.PENDING;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    private String note;

    protected Promotion() {}

    public Promotion(String id, String app, String groupName, String srcHost, String jar,
                     String gitHash, String prevHash, String branch, String sizeLabel, String requestedBy) {
        this.id = id;
        this.app = app;
        this.groupName = groupName;
        this.srcHost = srcHost;
        this.jar = jar;
        this.gitHash = gitHash;
        this.prevHash = prevHash;
        this.branch = branch;
        this.sizeLabel = sizeLabel;
        this.requestedBy = requestedBy;
        this.status = PromotionStatus.PENDING;
    }

    public void decide(PromotionStatus s, String by, String note) {
        this.status = s;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.note = note;
    }

    public void markDeployed() {
        this.status = PromotionStatus.DEPLOYED;
    }

    // getters
    public String getId() { return id; }
    public String getApp() { return app; }
    public String getGroupName() { return groupName; }
    public String getSrcHost() { return srcHost; }
    public String getJar() { return jar; }
    public String getGitHash() { return gitHash; }
    public String getPrevHash() { return prevHash; }
    public String getBranch() { return branch; }
    public String getSizeLabel() { return sizeLabel; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public PromotionStatus getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getNote() { return note; }
}
