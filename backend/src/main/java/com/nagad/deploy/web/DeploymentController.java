package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.DeploymentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DeploymentController {

    private final DeploymentService deployments;
    private final CurrentUser current;

    public DeploymentController(DeploymentService deployments, CurrentUser current) {
        this.deployments = deployments;
        this.current = current;
    }

    /** Managed groups + per-app approval state for the Deploy build step. */
    @GetMapping("/deploy/groups")
    public List<DeployGroupView> groups() {
        return deployments.deployGroups();
    }

    /** Jars staged in files/jars/ (ready to deploy) with their git hash, vs what prod runs. */
    @GetMapping("/deploy/staged-jars")
    public List<StagedJarView> stagedJars() {
        return deployments.stagedJars();
    }

    /** Remove one staged jar from files/jars/ (un-stage a fetched build). Needs write (w). */
    @DeleteMapping("/deploy/staged-jars/{jar}")
    public List<StagedJarView> removeStagedJar(@PathVariable("jar") String jar) {
        deployments.removeStagedJar(current.require(), jar);
        return deployments.stagedJars();
    }

    /** Validate and register a run; returns the id to stream. */
    @PostMapping("/deploy")
    public DeployStartedResponse start(@RequestBody DeployRequest req) {
        return deployments.start(current.require(), req);
    }

    /** Resolve consolidated host:app pairs into the review model (group, jar, prod & target hashes). */
    @PostMapping("/deploy/consolidated/resolve")
    public List<ConsolidatedPairView> resolvePairs(@RequestBody DeployConsolidatedRequest req) {
        return deployments.resolvePairs(current.require(), req.pairs());
    }

    /** Validate and register a consolidated (mixed-group) run; returns the id to stream. */
    @PostMapping("/deploy/consolidated")
    public DeployStartedResponse startConsolidated(@RequestBody DeployConsolidatedRequest req) {
        return deployments.startConsolidated(current.require(), req);
    }

    /**
     * Live per-host / terminal output for a run. Authorised by the single-use stream ticket
     * returned from POST /deploy (EventSource cannot send an Authorization header, so a
     * long-lived bearer token must never travel in this URL).
     */
    @GetMapping(value = "/deploy/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("ticket") String ticket) {
        return deployments.stream(ticket);
    }

    /** Portal-UI channel catalog: UIs, modes, prod DMZ hosts and the staging source. */
    @GetMapping("/deploy/portal-ui")
    public PortalUiCatalog portalUiCatalog() {
        return deployments.portalUiCatalog();
    }

    /** Validate and register a portal-ui run (fetch/deploy/rollback/verify); returns the id to stream. */
    @PostMapping("/deploy/portal-ui")
    public DeployStartedResponse startPortalUi(@RequestBody PortalUiRequest req) {
        return deployments.startPortalUi(current.require(), req);
    }

    @GetMapping("/deployments")
    public List<DeploymentView> history() {
        return deployments.history();
    }
}
