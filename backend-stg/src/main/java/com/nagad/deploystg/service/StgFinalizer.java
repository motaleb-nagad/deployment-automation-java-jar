package com.nagad.deploystg.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagad.deploystg.repo.DeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Commits the persistent effects of a completed staging run in one transaction — closes out the
 * deployment row, appends the audit entry and mails the report. Separate bean so the
 * {@code @Transactional} proxy applies from the streaming worker thread.
 */
@Service
public class StgFinalizer {

    private final DeploymentRepository deployments;
    private final MailService mail;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public StgFinalizer(DeploymentRepository deployments, MailService mail, AuditService audit) {
        this.deployments = deployments;
        this.mail = mail;
        this.audit = audit;
    }

    @Transactional
    public List<Map<String, String>> commit(String deploymentId, String actor, String group, String host,
                                             List<String> apps, List<String> actions, String cmd,
                                             String lastLogLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        String outcome = String.join(" → ", actions);
        for (String app : apps) {
            rows.add(Map.of("host", host, "app", app, "before", "staging", "after", outcome,
                    "verdict", "changed"));
        }
        deployments.findById(deploymentId).ifPresent(d -> d.complete("ok", "—", serialize(rows), lastLogLine));
        audit.record(actor, "stg-deploy", "stg-" + group + " " + String.join(",", apps),
                String.join(",", actions) + " @ " + host);
        mail.send("devops-team@nagad.com.bd", "Staging deploy complete — " + deploymentId, "Ran " + cmd);
        return rows;
    }

    @Transactional
    public List<Map<String, String>> commitPortalUi(String deploymentId, String actor, String host,
                                                     List<String> uis, String date, String cmd,
                                                     String lastLogLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        String d = date == null ? "" : date.trim();
        String after = "deployed" + (d.isEmpty() ? "" : " (backup " + d + ")");
        for (String ui : uis) {
            rows.add(Map.of("host", host, "app", ui, "before", "staging", "after", after,
                    "verdict", "changed"));
        }
        deployments.findById(deploymentId).ifPresent(d2 -> d2.complete("ok", "—", serialize(rows), lastLogLine));
        audit.record(actor, "stg-portal-ui", "deploy " + String.join(",", uis), "@ " + host);
        mail.send("devops-team@nagad.com.bd", "Staging portal-UI deploy complete — " + deploymentId, "Ran " + cmd);
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
