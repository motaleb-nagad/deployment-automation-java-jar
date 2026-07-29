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
                             String scope, boolean r, boolean w, boolean x, String perms,
                             boolean mustChangePassword) {}

    /** First-login (or self-service) password change: current password + the new one. */
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /** Forgot-password: the account (username / email) to mail a temporary password to. */
    public record ForgotPasswordRequest(String username) {}

    /** Always the same generic message (existence is never revealed). {@code demoTempPassword}
     *  is populated only in mail-simulate mode so the flow is testable without a live relay. */
    public record ForgotPasswordResponse(String message, String demoTempPassword) {}

    // ---- promotion / approval ----
    public record FetchRequest(String srcGroup, List<String> apps) {}

    /** hash = the fetched staging build's hash; prodHash = what production runs right now;
     *  deployed = whether this fetched jar has been rolled out to production yet. */
    public record PromotionView(String id, String app, String group, String srcHost, String jar,
                                String hash, String prevHash, String prodHash, boolean deployed,
                                String branch, String size,
                                String requestedBy, String requestedAt, String status,
                                String decidedBy, String decidedAt, String note) {}

    public record DenyRequest(String reason) {}

    // ---- deployment ----
    public record DeployRequest(String group, List<String> hosts, List<String> apps,
                                List<String> actions, String sudoPassword) {}

    /** One host:app target of a consolidated (mixed-group) run. */
    public record DeployPair(String host, String app) {}

    /** Consolidated deploy — arbitrary host:app pairs spanning different groups, one run.
     *  Maps to the {@code ./deploy.sh "host:app host:app" actions} wrapper. */
    public record DeployConsolidatedRequest(List<DeployPair> pairs, List<String> actions) {}

    /** A resolved consolidated target for the review step: the host, its group, the app,
     *  the jar, current prod hash and the target (fetched) hash if one is approved. */
    public record ConsolidatedPairView(String host, String group, String app, String jar,
                                       String prodHash, String targetHash, boolean approved) {}

    /** streamTicket is a single-use, short-lived credential for opening the SSE stream —
     *  it keeps the long-lived bearer token out of the stream URL (and therefore out of logs). */
    public record DeployStartedResponse(String deploymentId, String streamTicket) {}

    /** prodHash = what production runs right now; approvedHash = the fetched jar staged to deploy. */
    public record DeployAppView(String key, String jar, boolean approved, String approvedHash, String prodHash) {}

    public record DeployGroupView(String key, String cmd, String zone, String tier,
                                  List<String> hosts, List<DeployAppView> apps) {}

    public record DeploymentView(String id, String promotionId, String group, String hosts,
                                 String apps, String actions, String startedBy, String startedAt,
                                 String duration, String result, String beforeAfter, String logExcerpt) {}

    // ---- portal-ui channel (fetch / deploy / rollback / verify of the DMZ portal UIs) ----
    public record PortalUiHost(String host, String ip) {}

    public record PortalUiCatalog(List<String> uis, List<String> modes,
                                  List<PortalUiHost> prodHosts, PortalUiHost staging) {}

    /** mode = fetch | deploy | rollback | verify. hosts empty = all DMZ hosts (deploy/rollback).
     *  fixUrl/fixSize apply to deploy; date is the backup suffix (deploy) or which backup
     *  to restore (rollback). Maps to portalui-deployment/run.sh. */
    public record PortalUiRequest(String mode, List<String> uis, List<String> hosts,
                                  boolean fixUrl, boolean fixSize, String date) {}

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

    /** Super-admin provisions an account with an initial password. */
    public record CreateUserRequest(String username, String name, String email, String role,
                                    String scope, boolean r, boolean w, boolean x, String password) {}

    // ---- audit ----
    public record AuditRow(String ts, String actor, String verb, String target, String detail) {}
}
