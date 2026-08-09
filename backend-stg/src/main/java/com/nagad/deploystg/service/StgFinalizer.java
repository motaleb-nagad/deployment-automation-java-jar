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
    private final StgInventory inv;
    private final ObjectMapper json = new ObjectMapper();

    public StgFinalizer(DeploymentRepository deployments, MailService mail, AuditService audit, StgInventory inv) {
        this.deployments = deployments;
        this.mail = mail;
        this.audit = audit;
        this.inv = inv;
    }

    /**
     * The overall run result from the post-action process verdicts: {@code incident} when any
     * service failed to reach its expected state (start left it down, or stop left it up),
     * otherwise {@code ok}. {@code unverified} rows are surfaced per-row but do not, on their own,
     * mark the whole run an incident.
     */
    public static String resultOf(Map<String, String> stateVerdicts) {
        if (stateVerdicts == null) return "ok";
        boolean incident = stateVerdicts.values().stream()
                .anyMatch(v -> "not-running".equals(v) || "still-running".equals(v));
        return incident ? "incident" : "ok";
    }

    private static List<String> failures(Map<String, String> stateVerdicts) {
        List<String> out = new ArrayList<>();
        if (stateVerdicts == null) return out;
        stateVerdicts.forEach((k, v) -> {
            if ("not-running".equals(v) || "still-running".equals(v)) out.add(k + " (" + v + ")");
        });
        return out;
    }

    @Transactional
    public List<Map<String, String>> commit(String deploymentId, String actor, String group, String host,
                                             List<String> apps, List<String> actions, String cmd,
                                             String lastLogLine, Map<String, String> stateVerdicts) {
        List<Map<String, String>> rows = new ArrayList<>();
        String outcome = String.join(" → ", actions);
        Map<String, String> verdicts = stateVerdicts == null ? Map.of() : stateVerdicts;
        for (String app : apps) {
            // Per-app host so npsb-zone rows show the right server; single-host groups fall back.
            String h = inv.hostFor(group, app);
            if (h == null || h.isBlank()) h = host;
            // When the run had a stop/start phase the badge reports the verified live state.
            String verdict = verdicts.getOrDefault(h + ":" + app, "changed");
            rows.add(Map.of("host", h, "app", app, "before", "staging", "after", outcome, "verdict", verdict));
        }
        String result = resultOf(verdicts);
        List<String> failed = failures(verdicts);
        String incidentNote = failed.isEmpty() ? "" : " — INCIDENT: " + String.join(", ", failed);
        deployments.findById(deploymentId).ifPresent(d -> d.complete(result, "—", serialize(rows), lastLogLine));
        audit.record(actor, "stg-deploy", "stg-" + group + " " + String.join(",", apps),
                String.join(",", actions) + " @ " + host + incidentNote);
        mail.send("devops-team@nagad.com.bd",
                (failed.isEmpty() ? "Staging deploy complete — " : "Staging deploy INCIDENT — ") + deploymentId,
                "Ran " + cmd + incidentNote);
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
