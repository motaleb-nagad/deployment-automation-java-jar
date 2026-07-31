package com.nagad.deploy.service;

import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.PromotionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagad.deploy.repo.DeploymentRepository;
import com.nagad.deploy.repo.JarRegistryRepository;
import com.nagad.deploy.repo.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Commits the persistent effects of a completed run in one transaction — advance the jar
 * registry, mark the promotion deployed, close out the deployment row, and append the audit
 * entry. Separate bean so the {@code @Transactional} proxy applies when called from the
 * streaming worker thread.
 */
@Service
public class DeploymentFinalizer {

    private final FleetInventory inv;
    private final PromotionRepository promos;
    private final JarRegistryRepository registry;
    private final DeploymentRepository deployments;
    private final MailService mail;
    private final AuditService audit;
    private final ProdHashService prodHash;
    private final ObjectMapper json = new ObjectMapper();

    public DeploymentFinalizer(FleetInventory inv, PromotionRepository promos, JarRegistryRepository registry,
                               DeploymentRepository deployments, MailService mail, AuditService audit,
                               ProdHashService prodHash) {
        this.inv = inv;
        this.promos = promos;
        this.registry = registry;
        this.deployments = deployments;
        this.mail = mail;
        this.audit = audit;
        this.prodHash = prodHash;
    }

    @Transactional
    public List<Map<String, String>> commit(String deploymentId, String actor, String groupCmd,
                                            List<String> hosts, List<String> apps, List<String> actions,
                                            String cmd, String lastLogLine, Map<String, String> beforeHashes) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean isDeploy = actions.contains("deploy");
        Map<String, String> before0 = beforeHashes == null ? Map.of() : beforeHashes;

        for (String app : apps) {
            // "before" is the pre-run snapshot captured at start(); fall back to a live read.
            String before = before0.getOrDefault(app, prodHash.current(groupCmd, app));
            String after = before;
            if (isDeploy) {
                Optional<Promotion> approved = promos
                        .findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(groupCmd, app, PromotionStatus.APPROVED);
                if (approved.isPresent()) {
                    Promotion p = approved.get();
                    after = p.getGitHash();
                    p.markDeployed();
                    registry.findById(new JarRegistry.Key(app, groupCmd)).ifPresentOrElse(
                            r -> r.updateHash(p.getGitHash(), actor),
                            () -> registry.save(new JarRegistry(app, groupCmd, p.getJar(), p.getGitHash(), actor)));
                } else {
                    // Deployed without a console fetch — re-read the live jar so "after" is real.
                    after = prodHash.refreshed(groupCmd, app);
                }
            }
            for (String h : hosts) {
                rows.add(Map.of("host", h, "app", app, "before", before, "after", after,
                        "verdict", before.equals(after) ? "unchanged" : "changed"));
            }
        }

        String finalAfter = rows.isEmpty() ? "-" : rows.get(rows.size() - 1).get("after");
        deployments.findById(deploymentId).ifPresent(d ->
                d.complete("ok", "—", serialize(rows), lastLogLine));
        audit.record(actor, "deploy", groupCmd + " " + String.join(",", apps),
                String.join(",", actions) + " " + AnsibleRunner.hostExpr(hosts) + " → " + finalAfter);
        mail.send("devops-team@nagad.com.bd", "Deploy complete — " + deploymentId, "Ran " + cmd);
        return rows;
    }

    /**
     * Consolidated commit — one row per host:app pair (not a cartesian product), each routed to
     * the group its host belongs to. Advances the registry and marks promotions deployed exactly
     * as the per-group path does.
     */
    @Transactional
    public List<Map<String, String>> commitConsolidated(String deploymentId, String actor,
                                                        List<com.nagad.deploy.dto.Dtos.DeployPair> pairs,
                                                        List<String> actions, String cmd, String lastLogLine,
                                                        java.util.Set<String> skipped, Map<String, String> beforeHashes) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean isDeploy = actions.contains("deploy");
        java.util.Set<String> skip = skipped == null ? java.util.Set.of() : skipped;
        Map<String, String> before0 = beforeHashes == null ? Map.of() : beforeHashes;
        int skippedCount = 0;

        for (var p : pairs) {
            String host = p.host(), app = p.app();
            // Service not installed on this host — skipped by the run, not deployed.
            if (skip.contains(host + ":" + app)) {
                String prod = before0.getOrDefault(host + ":" + app,
                        prodHash.current(inv.groupForHost(host).map(FleetInventory.Group::cmd).orElse("consolidated"), app));
                rows.add(Map.of("host", host, "app", app, "before", prod, "after", prod, "verdict", "skipped"));
                skippedCount++;
                continue;
            }
            FleetInventory.Group g = inv.groupForHost(host).orElse(null);
            String group = g != null ? g.cmd() : "consolidated";
            String before = before0.getOrDefault(host + ":" + app, prodHash.current(group, app));
            String after = before;
            if (isDeploy && g != null) {
                Optional<Promotion> approved = promos
                        .findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(group, app, PromotionStatus.APPROVED);
                if (approved.isPresent()) {
                    Promotion pr = approved.get();
                    after = pr.getGitHash();
                    pr.markDeployed();
                    final String g2 = group;
                    registry.findById(new JarRegistry.Key(app, group)).ifPresentOrElse(
                            r -> r.updateHash(pr.getGitHash(), actor),
                            () -> registry.save(new JarRegistry(app, g2, pr.getJar(), pr.getGitHash(), actor)));
                } else {
                    after = prodHash.refreshed(group, app);
                }
            }
            rows.add(Map.of("host", host, "app", app, "before", before, "after", after,
                    "verdict", before.equals(after) ? "unchanged" : "changed"));
        }

        String finalAfter = rows.isEmpty() ? "-" : rows.get(rows.size() - 1).get("after");
        String skipNote = skippedCount > 0 ? " (" + skippedCount + " skipped — not installed on host)" : "";
        deployments.findById(deploymentId).ifPresent(d ->
                d.complete("ok", "—", serialize(rows), lastLogLine));
        String targets = pairs.stream().map(p -> p.host() + ":" + p.app())
                .collect(java.util.stream.Collectors.joining(" "));
        audit.record(actor, "deploy", "consolidated " + targets,
                String.join(",", actions) + " → " + finalAfter + skipNote);
        mail.send("devops-team@nagad.com.bd", "Consolidated deploy complete — " + deploymentId,
                "Ran " + cmd + skipNote);
        return rows;
    }

    /**
     * Portal-UI commit — records one row per host×ui with the mode's outcome. UI tarball
     * deploys have no jar-registry hash, so nothing in the registry changes; this just closes
     * out the deployment row, writes the audit entry and mails the report.
     */
    @Transactional
    public List<Map<String, String>> commitPortalUi(String deploymentId, String actor,
                                                    com.nagad.deploy.dto.Dtos.PortalUiRequest pui,
                                                    List<String> hosts, String cmd, String lastLogLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        String mode = pui.mode();
        String date = pui.date() == null ? "" : pui.date().trim();
        for (String ui : pui.uis()) {
            String after = switch (mode) {
                case "fetch" -> "fetched from staging";
                case "verify" -> "verified vs staging";
                case "rollback" -> "rolled back" + (date.isEmpty() ? " (newest backup)" : " to " + date);
                default -> "deployed" + (pui.fixUrl() ? " +url-fix" : "") + (pui.fixSize() ? " +size-fix" : "");
            };
            String verdict = "verify".equals(mode) ? "unchanged" : "changed";
            for (String h : hosts) {
                rows.add(Map.of("host", h, "app", ui, "before", mode, "after", after, "verdict", verdict));
            }
        }
        deployments.findById(deploymentId).ifPresent(d ->
                d.complete("ok", "—", serialize(rows), lastLogLine));
        audit.record(actor, "portal-ui", mode + " " + String.join(",", pui.uis()),
                AnsibleRunner.hostExpr(hosts) + " → " + mode);
        mail.send("devops-team@nagad.com.bd", "Portal-UI " + mode + " complete — " + deploymentId, "Ran " + cmd);
        return rows;
    }

    private String serialize(List<Map<String, String>> rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
