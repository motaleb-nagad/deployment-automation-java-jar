package com.nagad.deploy.service;

import com.nagad.deploy.domain.*;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.*;
import com.nagad.deploy.service.AnsibleRunner.Line;
import com.nagad.deploy.service.FleetInventory.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.http.HttpStatus.*;

/**
 * The DEPLOY flow. Enforces the governance gate — a {@code deploy} action only runs against
 * apps with an <em>approved</em> promotion for the target group — then streams the run live
 * and, on success, advances the registry and marks the promotion deployed.
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

    private final FleetInventory inv;
    private final AnsibleRunner runner;
    private final PromotionRepository promos;
    private final DeploymentRepository deployments;
    private final DeploymentFinalizer finalizer;
    private final ExecutorService executor;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    private final AtomicInteger seq = new AtomicInteger(5100);
    // Keyed by single-use stream ticket, not by deployment id, so the stream URL never
    // carries a reusable or guessable credential.
    private final Map<String, RunPlan> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record RunPlan(String deploymentId, String actor, Group group, List<String> hosts,
                           List<String> apps, List<String> actions, String cmd, List<Line> lines) {}

    public DeploymentService(FleetInventory inv, AnsibleRunner runner, PromotionRepository promos,
                             DeploymentRepository deployments, DeploymentFinalizer finalizer,
                             ExecutorService runExecutor) {
        this.inv = inv;
        this.runner = runner;
        this.promos = promos;
        this.deployments = deployments;
        this.finalizer = finalizer;
        this.executor = runExecutor;
    }

    /** Validate + persist the run, returning its id. The client then opens the SSE stream. */
    @Transactional
    public DeployStartedResponse start(AppUser actor, DeployRequest req) {
        if (!actor.isPermX()) {
            throw new ResponseStatusException(FORBIDDEN, "executing a deploy needs the execute (x) permission");
        }
        Group g = resolveGroup(req.group());
        if (!actor.canAccessGroup(g.cmd())) {
            throw new ResponseStatusException(FORBIDDEN, "you are not scoped to group " + g.cmd());
        }
        if (req.apps() == null || req.apps().isEmpty() || req.actions() == null || req.actions().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "pick at least one app and one action");
        }
        List<String> hosts = (req.hosts() == null || req.hosts().isEmpty())
                ? g.hosts().stream().map(FleetInventory.Host::name).toList() : req.hosts();

        // Governance gate: a deploy action requires an approved promotion per app+group.
        if (req.actions().contains("deploy")) {
            for (String app : req.apps()) {
                promos.findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(g.cmd(), app, PromotionStatus.APPROVED)
                        .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                                "no approved promotion for " + app + " in " + g.cmd() + " — fetch and get it approved first"));
            }
        }

        String id = "DP-" + seq.getAndIncrement();
        String cmd = runner.command(g.cmd(), hosts, req.apps(), req.actions());
        deployments.save(new Deployment(id, null, g.cmd(), AnsibleRunner.hostExpr(hosts),
                String.join(",", req.apps()), String.join(",", req.actions()), actor.getUsername()));

        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        pending.put(ticket, new RunPlan(id, actor.getUsername(), g, hosts, req.apps(), req.actions(), cmd,
                runner.script(g, hosts, req.apps(), req.actions(), inv, cmd)));
        return new DeployStartedResponse(id, ticket);
    }

    /** SSE stream of the run, authorised by the single-use ticket from {@link #start}. */
    public SseEmitter stream(String ticket) {
        RunPlan plan = ticket == null ? null : pending.remove(ticket); // single use — removed on connect
        SseEmitter emitter = new SseEmitter(0L); // no timeout — a run can take minutes
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
            for (Line ln : plan.lines()) {
                emitter.send(SseEmitter.event().name("line").data(ln));
                if (ln.railHost() != null) {
                    emitter.send(SseEmitter.event().name("host").data(Map.of(
                            "host", ln.railHost(), "action", ln.railAction(), "state", ln.railState())));
                }
                Thread.sleep(simulate ? 130 : 0);
            }
            String lastLog = plan.lines().get(plan.lines().size() - 2).text();
            List<Map<String, String>> rows = finalizer.commit(plan.deploymentId(), plan.actor(),
                    plan.group().cmd(), plan.hosts(), plan.apps(), plan.actions(), plan.cmd(), lastLog);
            emitter.send(SseEmitter.event().name("complete").data(Map.of(
                    "deploymentId", plan.deploymentId(), "result", "ok", "rows", rows)));
            emitter.complete();
        } catch (Exception e) {
            log.warn("deploy stream {} failed: {}", plan.deploymentId(), e.toString());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    /** Managed groups with their hosts and per-app approval state, for the Deploy panel. */
    @Transactional(readOnly = true)
    public List<DeployGroupView> deployGroups() {
        List<DeployGroupView> out = new ArrayList<>();
        for (Group g : inv.managedGroups()) {
            List<DeployAppView> apps = g.svcs().stream().map(s -> {
                Optional<Promotion> ap = promos.findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(
                        g.cmd(), s.key(), PromotionStatus.APPROVED);
                return new DeployAppView(s.key(), s.jar(), ap.isPresent(),
                        ap.map(Promotion::getGitHash).orElse(null));
            }).toList();
            out.add(new DeployGroupView(g.key(), g.cmd(), g.zone(), g.tier(),
                    g.hosts().stream().map(FleetInventory.Host::name).toList(), apps));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<DeploymentView> history() {
        return deployments.findAllByOrderByStartedAtDesc().stream().map(d -> new DeploymentView(
                d.getId(), d.getPromotionId(), d.getGroupName(), d.getHosts(), d.getApps(), d.getActions(),
                d.getStartedBy(), PromotionService.fmt(d.getStartedAt()), d.getDuration(), d.getResult(),
                d.getBeforeAfter(), d.getLogExcerpt())).toList();
    }

    private Group resolveGroup(String wrapperOrKey) {
        return inv.managedGroups().stream()
                .filter(g -> g.cmd().equals(wrapperOrKey) || g.key().equals(wrapperOrKey))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "unknown group " + wrapperOrKey));
    }

}
