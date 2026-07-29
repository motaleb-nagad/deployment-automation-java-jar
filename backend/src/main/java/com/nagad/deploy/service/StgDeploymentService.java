package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.Deployment;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.DeploymentRepository;
import com.nagad.deploy.service.AnsibleRunner.Line;
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
 * The <strong>staging</strong> deployment flow ({@code stg-deployment} bundle). Unlike the prod
 * channel — which fetches built jars from staging and gates them behind approval — staging is
 * <em>upload-driven</em>: the operator manually uploads a jar / config / UI tarball, the portal
 * stages it into the bundle on the jump host, then runs the staging wrapper:
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
    private final FleetInventory fleet;
    private final DeploymentRepository deployments;
    private final StgFinalizer finalizer;
    private final ExecutorService executor;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    // Shell-safe token: anything reaching the wrapper command line may only contain these chars.
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("^[0-9]{8}$");
    private static final long MAX_UPLOAD_BYTES = 512L * 1024 * 1024; // 512 MB

    private final AtomicInteger seq = new AtomicInteger(9100);
    private final Map<String, RunPlan> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record RunPlan(String deploymentId, String actor, boolean portalUi,
                           String group, String host, List<String> apps, List<String> actions,
                           List<String> uis, String date, String cmd, List<Line> lines) {}

    public StgDeploymentService(StgInventory inv, StgAnsibleRunner runner, FleetInventory fleet,
                                DeploymentRepository deployments, StgFinalizer finalizer,
                                ExecutorService runExecutor) {
        this.inv = inv;
        this.runner = runner;
        this.fleet = fleet;
        this.deployments = deployments;
        this.finalizer = finalizer;
        this.executor = runExecutor;
    }

    // ---- catalog ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StgCatalog catalog() {
        List<StgGroupView> groups = inv.groups().stream().map(g -> new StgGroupView(
                g.key(), g.label(), g.host(), g.ip(),
                inv.apps(g.key()).stream().map(a -> new StgAppView(a.key(), a.jar())).toList()
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
    public StgUploadResponse upload(AppUser actor, String kind, String group, String target, MultipartFile file) {
        if (!actor.isPermW()) {
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
                // A config file belongs to an app in one of the groups.
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
            String path = runner.stageUpload(simulate, relDir, storedName, file.getBytes());
            return new StgUploadResponse(k, tgt, storedName, path, file.getSize());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("stg upload {} {} failed: {}", k, tgt, e.toString());
            throw new ResponseStatusException(BAD_GATEWAY, "could not stage upload on jump host: " + e.getMessage());
        }
    }

    // ---- jar/config deploy ------------------------------------------------------------------

    @Transactional
    public DeployStartedResponse startDeploy(AppUser actor, StgDeployRequest req) {
        if (!actor.isPermX()) {
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
        String cmd = runner.command(group, req.apps(), req.actions());
        String id = "STG-" + seq.getAndIncrement();
        deployments.save(new Deployment(id, null, "stg-" + group, g.host(),
                String.join(",", req.apps()), String.join(",", req.actions()), actor.getUsername()));

        RunPlan plan = new RunPlan(id, actor.getUsername(), false, group, g.host(),
                req.apps(), req.actions(), null, null, cmd,
                runner.script(group, g.host(), req.apps(), req.actions(), inv, fleet, cmd));
        return register(plan);
    }

    // ---- portal-ui deploy -------------------------------------------------------------------

    @Transactional
    public DeployStartedResponse startPortalUi(AppUser actor, StgPortalUiRequest req) {
        if (!actor.isPermX()) {
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
        String cmd = runner.portalUiCommand(req.uis(), date);
        String id = "STG-" + seq.getAndIncrement();
        deployments.save(new Deployment(id, null, "stg-portal-ui", host,
                String.join(",", req.uis()), "deploy", actor.getUsername()));

        RunPlan plan = new RunPlan(id, actor.getUsername(), true, "portal", host,
                null, List.of("deploy"), req.uis(), date, cmd,
                runner.scriptPortalUi(req.uis(), date, host, fleet, cmd));
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

    /** SSE stream of a staging run, authorised by the single-use ticket from the start call. */
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
                ? runner.executePortalUi(plan.uis(), plan.date(), sink)
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

    private String requireGroup(String group) {
        String g = group == null ? "" : group.trim();
        if (inv.group(g).isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown staging group " + g + " (use: core | portal)");
        }
        return g;
    }
}
