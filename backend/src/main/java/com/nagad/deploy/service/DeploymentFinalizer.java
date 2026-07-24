package com.nagad.deploy.service;

import com.nagad.deploy.domain.JarRegistry;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.PromotionStatus;
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

    public DeploymentFinalizer(FleetInventory inv, PromotionRepository promos, JarRegistryRepository registry,
                               DeploymentRepository deployments, MailService mail, AuditService audit) {
        this.inv = inv;
        this.promos = promos;
        this.registry = registry;
        this.deployments = deployments;
        this.mail = mail;
        this.audit = audit;
    }

    @Transactional
    public List<Map<String, String>> commit(String deploymentId, String actor, String groupCmd,
                                            List<String> hosts, List<String> apps, List<String> actions,
                                            String cmd, String lastLogLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean isDeploy = actions.contains("deploy");

        for (String app : apps) {
            String before = inv.hash(groupCmd, app);
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

    private static String serialize(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"host\":\"").append(r.get("host")).append("\",\"before\":\"").append(r.get("before"))
              .append("\",\"after\":\"").append(r.get("after")).append("\"}");
        }
        return sb.append(']').toString();
    }
}
