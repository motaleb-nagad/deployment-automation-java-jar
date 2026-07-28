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
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    private final ProdHashService prodHash;
    private final ExecutorService executor;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    // Shell-safe token: names that reach the run.sh command line may only contain these chars.
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");

    private final AtomicInteger seq = new AtomicInteger(5100);
    // Keyed by single-use stream ticket, not by deployment id, so the stream URL never
    // carries a reusable or guessable credential.
    private final Map<String, RunPlan> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private record RunPlan(String deploymentId, String actor, Group group, List<String> hosts,
                           List<String> apps, List<String> actions, String cmd, List<Line> lines,
                           boolean consolidated, List<DeployPair> pairs) {}

    public DeploymentService(FleetInventory inv, AnsibleRunner runner, PromotionRepository promos,
                             DeploymentRepository deployments, DeploymentFinalizer finalizer,
                             ProdHashService prodHash, ExecutorService runExecutor) {
        this.inv = inv;
        this.runner = runner;
        this.promos = promos;
        this.deployments = deployments;
        this.finalizer = finalizer;
        this.prodHash = prodHash;
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

        // Whitelist every element that ends up on the wrapper command line, so a request can
        // never smuggle shell metacharacters into the remote ./run.sh invocation. The wrapper
        // treats every sub-group sharing a cmd as one target (e.g. "core" spans nagad-app,
        // -dkyc, -limited, -txn-history), so validate against the union of that whole family.
        List<Group> family = inv.managedGroups().stream().filter(x -> x.cmd().equals(g.cmd())).toList();
        Set<String> validHosts = family.stream().flatMap(x -> x.hosts().stream())
                .map(FleetInventory.Host::name).collect(java.util.stream.Collectors.toSet());
        Set<String> validApps = family.stream().flatMap(x -> x.svcs().stream())
                .map(FleetInventory.Svc::key).collect(java.util.stream.Collectors.toSet());
        for (String h : hosts) {
            if (!TOKEN.matcher(h).matches() || !validHosts.contains(h)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown host " + h + " in " + g.cmd());
            }
        }
        for (String a : req.apps()) {
            if (!TOKEN.matcher(a).matches() || !validApps.contains(a)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown app " + a + " in " + g.cmd());
            }
        }
        for (String act : req.actions()) {
            if (!Set.of("stop", "deploy", "start").contains(act)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown action " + act);
            }
        }

        // No approval gate: a fetched jar is deployable directly (super-admin approval retired).
        String id = "DP-" + seq.getAndIncrement();
        String cmd = runner.command(g.cmd(), hosts, req.apps(), req.actions());
        deployments.save(new Deployment(id, null, g.cmd(), AnsibleRunner.hostExpr(hosts),
                String.join(",", req.apps()), String.join(",", req.actions()), actor.getUsername()));

        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        pending.put(ticket, new RunPlan(id, actor.getUsername(), g, hosts, req.apps(), req.actions(), cmd,
                runner.script(g, hosts, req.apps(), req.actions(), inv, cmd), false, null));
        return new DeployStartedResponse(id, ticket);
    }

    /**
     * Consolidated (mixed-group) run — arbitrary host:app pairs spanning different groups in a
     * single command. Validates every pair against the merged jar_map and the caller's scope,
     * then registers the run exactly like a per-group deploy so it streams and finalizes the
     * same way. Maps to the {@code ./deploy.sh "host:app host:app" actions} wrapper.
     */
    @Transactional
    public DeployStartedResponse startConsolidated(AppUser actor, DeployConsolidatedRequest req) {
        if (!actor.isPermX()) {
            throw new ResponseStatusException(FORBIDDEN, "executing a deploy needs the execute (x) permission");
        }
        if (req.pairs() == null || req.pairs().isEmpty() || req.actions() == null || req.actions().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "pick at least one host:app pair and one action");
        }
        for (String act : req.actions()) {
            if (!Set.of("stop", "deploy", "start").contains(act)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown action " + act);
            }
        }

        List<DeployPair> pairs = new ArrayList<>();
        LinkedHashSet<String> hostSet = new LinkedHashSet<>();
        LinkedHashSet<String> appSet = new LinkedHashSet<>();
        for (DeployPair p : req.pairs()) {
            String host = p.host() == null ? "" : p.host().trim();
            String app = p.app() == null ? "" : p.app().trim();
            if (!TOKEN.matcher(host).matches() || !TOKEN.matcher(app).matches()) {
                throw new ResponseStatusException(BAD_REQUEST, "invalid host:app pair " + host + ":" + app);
            }
            Group hg = inv.groupForHost(host).orElseThrow(() ->
                    new ResponseStatusException(BAD_REQUEST, "unknown host " + host));
            if (!FleetInventory.JAR_MAP.containsKey(app)) {
                throw new ResponseStatusException(BAD_REQUEST, "unknown app " + app);
            }
            if (!actor.canAccessGroup(hg.cmd())) {
                throw new ResponseStatusException(FORBIDDEN,
                        "you are not scoped to group " + hg.cmd() + " (host " + host + ")");
            }
            pairs.add(new DeployPair(host, app));
            hostSet.add(host);
            appSet.add(app);
        }

        List<String> hosts = new ArrayList<>(hostSet);
        List<String> apps = new ArrayList<>(appSet);
        String id = "DP-" + seq.getAndIncrement();
        String cmd = runner.consolidatedCommand(pairs, req.actions());
        deployments.save(new Deployment(id, null, "consolidated", AnsibleRunner.hostExpr(hosts),
                String.join(",", apps), String.join(",", req.actions()), actor.getUsername()));

        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        pending.put(ticket, new RunPlan(id, actor.getUsername(), null, hosts, apps, req.actions(), cmd,
                runner.scriptConsolidated(pairs, req.actions(), inv, cmd), true, pairs));
        return new DeployStartedResponse(id, ticket);
    }

    /** Resolve the review model for a set of consolidated pairs — group, jar, prod & target hashes. */
    @Transactional(readOnly = true)
    public List<ConsolidatedPairView> resolvePairs(AppUser actor, List<DeployPair> in) {
        List<ConsolidatedPairView> out = new ArrayList<>();
        if (in == null) return out;
        for (DeployPair p : in) {
            String host = p.host() == null ? "" : p.host().trim();
            String app = p.app() == null ? "" : p.app().trim();
            Group hg = inv.groupForHost(host).orElse(null);
            if (hg == null || !FleetInventory.JAR_MAP.containsKey(app)) continue;
            String jar = FleetInventory.JAR_MAP.getOrDefault(app, app + "-1.0.jar");
            String prod = prodHash.current(hg.cmd(), app);
            Optional<Promotion> ap = promos.findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(
                    hg.cmd(), app, PromotionStatus.APPROVED);
            out.add(new ConsolidatedPairView(host, hg.cmd(), app, jar, prod,
                    ap.map(Promotion::getGitHash).orElse(null), ap.isPresent()));
        }
        return out;
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
            String lastLog = simulate ? streamScripted(plan, emitter) : streamReal(plan, emitter);
            List<Map<String, String>> rows = plan.consolidated()
                    ? finalizer.commitConsolidated(plan.deploymentId(), plan.actor(), plan.pairs(),
                            plan.actions(), plan.cmd(), lastLog)
                    : finalizer.commit(plan.deploymentId(), plan.actor(), plan.group().cmd(),
                            plan.hosts(), plan.apps(), plan.actions(), plan.cmd(), lastLog);
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

    /** Demo mode: replay the scripted lines with a small delay so the console reads live. */
    private String streamScripted(RunPlan plan, SseEmitter emitter) throws IOException, InterruptedException {
        for (Line ln : plan.lines()) {
            sendLine(emitter, ln);
            Thread.sleep(130);
        }
        return plan.lines().get(plan.lines().size() - 2).text();
    }

    /** Production: SSH to the jump host, run the real wrapper, stream stdout. Non-zero exit fails the run. */
    private String streamReal(RunPlan plan, SseEmitter emitter) throws IOException, InterruptedException {
        StringBuilder lastLog = new StringBuilder();
        Consumer<Line> sink = ln -> {
            try {
                sendLine(emitter, ln);
                if (ln.text() != null && !ln.text().isBlank()) {
                    lastLog.setLength(0);
                    lastLog.append(ln.text());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        int code = plan.consolidated()
                ? runner.executeConsolidated(plan.pairs(), plan.actions(), sink)
                : runner.execute(plan.group().cmd(), plan.hosts(), plan.apps(), plan.actions(), sink);
        if (code != 0) {
            throw new IllegalStateException((plan.consolidated() ? "deploy.sh" : "run.sh") + " exited with code " + code);
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

    /** Managed groups with their hosts and per-app approval state, for the Deploy panel. */
    @Transactional(readOnly = true)
    public List<DeployGroupView> deployGroups() {
        List<DeployGroupView> out = new ArrayList<>();
        for (Group g : inv.managedGroups()) {
            List<DeployAppView> apps = g.svcs().stream().map(s -> {
                Optional<Promotion> ap = promos.findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(
                        g.cmd(), s.key(), PromotionStatus.APPROVED);
                return new DeployAppView(s.key(), s.jar(), ap.isPresent(),
                        ap.map(Promotion::getGitHash).orElse(null), prodHash.current(g.cmd(), s.key()));
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
