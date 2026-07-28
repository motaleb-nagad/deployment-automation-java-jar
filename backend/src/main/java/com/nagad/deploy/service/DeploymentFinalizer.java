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
                                            String cmd, String lastLogLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean isDeploy = actions.contains("deploy");

        for (String app : apps) {
            String before = prodHash.current(groupCmd, app);
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
                                                        java.util.Set<String> skipped) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean isDeploy = actions.contains("deploy");
        java.util.Set<String> skip = skipped == null ? java.util.Set.of() : skipped;
        int skippedCount = 0;

        for (var p : pairs) {
            String host = p.host(), app = p.app();
            // Service not installed on this host — skipped by the run, not deployed.
            if (skip.contains(host + ":" + app)) {
                String prod = prodHash.current(inv.groupForHost(host).map(FleetInventory.Group::cmd).orElse("consolidated"), app);
                rows.add(Map.of("host", host, "app", app, "before", prod, "after", prod, "verdict", "skipped"));
                skippedCount++;
                continue;
            }
            FleetInventory.Group g = inv.groupForHost(host).orElse(null);
            String group = g != null ? g.cmd() : "consolidated";
            String before = prodHash.current(group, app);
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

    private String serialize(List<Map<String, String>> rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
