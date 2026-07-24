package com.nagad.deploy.dto;

import java.util.List;

/** Wire DTOs for the console API. Grouped as nested records for a single import surface. */
public final class Dtos {
    private Dtos() {}

    // ---- auth ----
    public record LoginRequest(String username, String password) {}

    /** step1Token identifies the pending login between the password and OTP steps. */
    public record LoginResponse(boolean otpRequired, String maskedEmail, String step1Token, String demoCode) {}

    public record VerifyRequest(String step1Token, String code) {}

    public record SessionResponse(String token, MeResponse user) {}

    public record MeResponse(String username, String name, String email, String role,
                             String scope, boolean r, boolean w, boolean x, String perms) {}

    // ---- promotion / approval ----
    public record FetchRequest(String srcGroup, List<String> apps) {}

    public record PromotionView(String id, String app, String group, String srcHost, String jar,
                                String hash, String prevHash, String branch, String size,
                                String requestedBy, String requestedAt, String status,
                                String decidedBy, String decidedAt, String note) {}

    public record DenyRequest(String reason) {}

    // ---- deployment ----
    public record DeployRequest(String group, List<String> hosts, List<String> apps,
                                List<String> actions, String sudoPassword) {}

    /** streamTicket is a single-use, short-lived credential for opening the SSE stream —
     *  it keeps the long-lived bearer token out of the stream URL (and therefore out of logs). */
    public record DeployStartedResponse(String deploymentId, String streamTicket) {}

    public record DeployAppView(String key, String jar, boolean approved, String approvedHash) {}

    public record DeployGroupView(String key, String cmd, String zone, String tier,
                                  List<String> hosts, List<DeployAppView> apps) {}

    public record DeploymentView(String id, String promotionId, String group, String hosts,
                                 String apps, String actions, String startedBy, String startedAt,
                                 String duration, String result, String beforeAfter, String logExcerpt) {}

    // ---- fleet ----
    public record ServiceCellView(String svc, String host, String status, String hash, String uptime,
                                  String pid, String ip, String jar, int instances, String lastDeployed) {}

    public record GroupView(String key, String cmd, String zone, String tier,
                            List<String> hosts, List<ServiceCellView> cells, List<String> issues) {}

    /** kind = DOWN | DRIFT | RESTART | UNKNOWN. */
    public record AttentionView(String kind, String title, String sub, String desc,
                                String group, String svc, String host) {}

    public record FleetView(String collectedAt, String age, boolean incident,
                            int hostsTotal, int servicesTotal, int instancesTotal,
                            int servicesDown, int driftGroups, int restarts, int unknowns,
                            List<AttentionView> attention, List<GroupView> groups) {}

    // ---- registry ----
    public record RegistryRow(String app, String group, String jar, String prodHash,
                              String latestHash, String latestStatus, String latestBy, String updatedAt) {}

    // ---- admin ----
    public record AdminRow(String username, String name, String role, String scope,
                           boolean r, boolean w, boolean x) {}

    // ---- audit ----
    public record AuditRow(String ts, String actor, String verb, String target, String detail) {}
}
