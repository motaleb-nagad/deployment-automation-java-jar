package com.nagad.deploystg.service;

import com.nagad.deploystg.domain.Deployment;
import com.nagad.deploystg.dto.Dtos.*;
import com.nagad.deploystg.repo.DeploymentRepository;
import com.nagad.deploystg.security.StgUser;
import com.nagad.deploystg.service.StgAnsibleRunner.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.springframework.http.HttpStatus.*;

/**
 * The <strong>staging</strong> deployment flow ({@code stg-deployment} bundle). Upload-driven:
 * the operator manually uploads a jar / config / UI tarball, this service stages it into the
 * bundle on the jump host, then runs the staging wrapper:
 * <ul>
 *   <li>jar/config: {@code ./run.sh <core|portal> all <apps> <actions>}</li>
 *   <li>portal-ui:  {@code portalui/run.sh <uis> [date]}</li>
 * </ul>
 * Streams live output over SSE (own single-use ticket) and records the run to history + audit.
 */
@Service
public class StgDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(StgDeploymentService.class);

    private final StgInventory inv;
    private final StgAnsibleRunner runner;
    private final DeploymentRepository deployments;
    private final StgFinalizer finalizer;
    private final ExecutorService executor;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("^[0-9]{8}$");
    private static final long MAX_UPLOAD_BYTES = 512L * 1024 * 1024; // 512 MB

    private final AtomicInteger seq = new AtomicInteger(9100);
    private final Map<String, RunPlan> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record RunPlan(String deploymentId, String actor, boolean portalUi,
                           String group, String host, List<String> apps, List<String> actions,
                           List<String> uis, String date, String cmd, List<Line> lines,
                           boolean urlFix, boolean sizeFix) {}

    public StgDeploymentService(StgInventory inv, StgAnsibleRunner runner,
                                DeploymentRepository deployments, StgFinalizer finalizer,
                                ExecutorService runExecutor) {
        this.inv = inv;
        this.runner = runner;
        this.deployments = deployments;
        this.finalizer = finalizer;
        this.executor = runExecutor;
    }

    // ---- history ----------------------------------------------------------------------------

    private static final java.time.format.DateTimeFormatter HIST_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(java.time.ZoneOffset.UTC);

    /** Everything done in staging — runs, portal-UI deploys and property edits — newest first. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<StgHistoryRow> history() {
        return deployments.findTop200ByGroupNameStartingWithOrderByStartedAtDesc("stg").stream()
                .map(d -> new StgHistoryRow(d.getId(), d.getGroupName(), d.getHosts(), d.getApps(),
                        d.getActions(), d.getStartedBy(),
                        d.getStartedAt() == null ? null : HIST_FMT.format(d.getStartedAt()),
                        d.getDuration(), d.getResult()))
                .toList();
    }

    // ---- catalog ----------------------------------------------------------------------------

    public StgCatalog catalog() {
        List<StgGroupView> groups = inv.groups().stream().map(g -> new StgGroupView(
                g.key(), g.label(), g.host(), g.ip(),
                inv.apps(g.key()).stream().map(a -> new StgAppView(a.key(), a.jar(), a.host(), a.ip())).toList()
        )).toList();
        return new StgCatalog(groups, StgInventory.UIS, runner.stgDir());
    }

    // ---- upload -----------------------------------------------------------------------------

    /**
     * Stage a manually-uploaded file into the staging bundle on the jump host. {@code kind} is
     * {@code jar} (needs a valid app → stored as the jar_map name), {@code cfg} (needs a valid
     * app → stored as {@code <app>-application.properties}) or {@code portalui} (needs a valid UI
     * → stored as {@code <ui>.tar}). Requires the write (w) permission.
     */
    public StgUploadResponse upload(StgUser actor, String kind, String group, String target, MultipartFile file) {
        if (!actor.w()) {
            throw new ResponseStatusException(FORBIDDEN, "uploading a build needs the write (w) permission");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "no file provided");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "file exceeds the 512 MB upload limit");
        }
        String k = kind == null ? "" : kind.trim();
        String tgt = target == null ? "" : target.trim();
        if (!TOKEN.matcher(tgt).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid target " + tgt);
        }

        String relDir;
        String storedName;
        switch (k) {
            case "jar" -> {
                String grp = requireGroup(group);
                storedName = inv.jarFor(grp, tgt).orElseThrow(() ->
                        new ResponseStatusException(BAD_REQUEST, "unknown app " + tgt + " in " + grp));
                relDir = StgAnsibleRunner.JARS_DIR;
            }
            case "cfg" -> {
                if (inv.jarFor("core", tgt).isEmpty() && inv.jarFor("portal", tgt).isEmpty()) {
                    throw new ResponseStatusException(BAD_REQUEST, "unknown app " + tgt);
                }
                storedName = tgt + "-application.properties";
                relDir = StgAnsibleRunner.CFG_DIR;
            }
            case "portalui" -> {
                if (!inv.isValidUi(tgt)) {
                    throw new ResponseStatusException(BAD_REQUEST, "unknown UI " + tgt);
                }
                storedName = tgt + ".tar";
                relDir = StgAnsibleRunner.PORTALUI_DIR;
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "unknown upload kind " + k);
        }

        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256Hex(bytes);
            String path = runner.stageUpload(simulate, relDir, storedName, bytes);
            return new StgUploadResponse(k, tgt, storedName, path, file.getSize(), sha256);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("stg upload {} {} failed: {}", k, tgt, e.toString());
            throw new ResponseStatusException(BAD_GATEWAY, "could not stage upload on jump host: " + e.getMessage());
        }
    }

    // ---- jar/config deploy ------------------------------------------------------------------

    @Transactional
    public DeployStartedResponse startDeploy(StgUser actor, StgDeployRequest req) {
        if (!actor.x()) {
            throw new ResponseStatusException(FORBIDDEN, "executing a deploy needs the execute (x) permission");
        }
        String group = requireGroup(req.group());
        if (req.apps() == null || req.apps().isEmpty() || req.actions() == null || req.actions().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "pick at least one app and one action");
        }
        for (String a : req.apps()) {
            if (!TOKEN.matcher(a == null ? "" : a).matches() || inv.jarFor(group, a).isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown app " + a + " in " + group);
            }
        }
        for (String act : req.actions()) {
            if (!Set.of("stop", "deploy", "start").contains(act)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown action " + act);
            }
        }
        StgInventory.Group g = inv.group(group).orElseThrow();
        // Distinct target hosts for the selected apps — one for core/portal, several for npsb-zone.
        String hostSummary = req.apps().stream().map(a -> inv.hostFor(group, a))
                .distinct().collect(java.util.stream.Collectors.joining(","));
        if (hostSummary.isBlank()) hostSummary = g.host();
        String cmd = runner.command(group, req.apps(), req.actions());
        String id = "STG-" + seq.getAndIncrement();
        deployments.save(new Deployment(id, null, "stg-" + group, hostSummary,
                String.join(",", req.apps()), String.join(",", req.actions()), actor.username()));

        RunPlan plan = new RunPlan(id, actor.username(), false, group, hostSummary,
                req.apps(), req.actions(), null, null, cmd,
                runner.script(group, g.host(), req.apps(), req.actions(), inv, cmd), false, false);
        return register(plan);
    }

    // ---- portal-ui deploy -------------------------------------------------------------------

    @Transactional
    public DeployStartedResponse startPortalUi(StgUser actor, StgPortalUiRequest req) {
        if (!actor.x()) {
            throw new ResponseStatusException(FORBIDDEN, "executing a deploy needs the execute (x) permission");
        }
        if (req.uis() == null || req.uis().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "pick at least one UI");
        }
        for (String ui : req.uis()) {
            if (!inv.isValidUi(ui)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown UI " + ui);
            }
        }
        String date = req.date() == null ? "" : req.date().trim();
        if (!date.isBlank() && !DATE.matcher(date).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "date must be DDMMYYYY (8 digits)");
        }
        String host = inv.group("portal").map(StgInventory.Group::host).orElse("ngd-dc-portal-01");
        String cmd = runner.portalUiCommand(req.uis(), date, req.urlFix(), req.sizeFix());
        String id = "STG-" + seq.getAndIncrement();
        deployments.save(new Deployment(id, null, "stg-portal-ui", host,
                String.join(",", req.uis()), req.urlFix() ? "deploy+url-fix" : "deploy", actor.username()));

        RunPlan plan = new RunPlan(id, actor.username(), true, "portal", host,
                null, List.of("deploy"), req.uis(), date, cmd,
                runner.scriptPortalUi(req.uis(), date, host, cmd, req.urlFix(), req.sizeFix()),
                req.urlFix(), req.sizeFix());
        return register(plan);
    }

    private DeployStartedResponse register(RunPlan plan) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        pending.put(ticket, plan);
        return new DeployStartedResponse(plan.deploymentId(), ticket);
    }

    // ---- SSE stream -------------------------------------------------------------------------

    public SseEmitter stream(String ticket) {
        RunPlan plan = ticket == null ? null : pending.remove(ticket); // single use
        SseEmitter emitter = new SseEmitter(0L);
        if (plan == null) {
            executor.submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data("unknown or already-streamed deployment"));
                } catch (IOException ignored) {}
                emitter.complete();
            });
            return emitter;
        }
        executor.submit(() -> runStream(plan, emitter));
        return emitter;
    }

    private void runStream(RunPlan plan, SseEmitter emitter) {
        try {
            String lastLog = simulate ? streamScripted(plan, emitter) : streamReal(plan, emitter);
            List<Map<String, String>> rows = plan.portalUi()
                    ? finalizer.commitPortalUi(plan.deploymentId(), plan.actor(), plan.host(),
                        plan.uis(), plan.date(), plan.cmd(), lastLog)
                    : finalizer.commit(plan.deploymentId(), plan.actor(), plan.group(), plan.host(),
                        plan.apps(), plan.actions(), plan.cmd(), lastLog);
            emitter.send(SseEmitter.event().name("complete").data(Map.of(
                    "deploymentId", plan.deploymentId(), "result", "ok", "rows", rows)));
            emitter.complete();
        } catch (Exception e) {
            log.warn("stg deploy stream {} failed: {}", plan.deploymentId(), e.toString());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    private String streamScripted(RunPlan plan, SseEmitter emitter) throws IOException, InterruptedException {
        for (Line ln : plan.lines()) {
            sendLine(emitter, ln);
            Thread.sleep(130);
        }
        return plan.lines().isEmpty() ? "" : plan.lines().get(plan.lines().size() - 1).text();
    }

    private String streamReal(RunPlan plan, SseEmitter emitter) throws IOException, InterruptedException {
        StringBuilder lastLog = new StringBuilder();
        Consumer<Line> sink = ln -> {
            try {
                sendLine(emitter, ln);
                String t = ln.text();
                if (t != null && !t.isBlank()) {
                    lastLog.setLength(0);
                    lastLog.append(t);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        int code = plan.portalUi()
                ? runner.executePortalUi(plan.uis(), plan.date(), plan.urlFix(), plan.sizeFix(), sink)
                : runner.execute(plan.group(), plan.apps(), plan.actions(), sink);
        if (code != 0) {
            throw new IllegalStateException((plan.portalUi() ? "portalui run.sh" : "run.sh")
                    + " exited with code " + code);
        }
        return lastLog.toString();
    }

    private void sendLine(SseEmitter emitter, Line ln) throws IOException {
        emitter.send(SseEmitter.event().name("line").data(ln));
        if (ln.railHost() != null) {
            emitter.send(SseEmitter.event().name("host").data(Map.of(
                    "host", ln.railHost(), "action", ln.railAction(), "state", ln.railState())));
        }
    }

    /** SHA-256 of the uploaded bytes, as lower-case hex — the hash of the jar/config/tar we stage. */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }

    private String requireGroup(String group) {
        String g = group == null ? "" : group.trim();
        if (inv.group(g).isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown staging group " + g + " (use: core | portal)");
        }
        return g;
    }
}
