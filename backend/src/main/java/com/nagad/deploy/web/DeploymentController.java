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

    /** Validate and register a run; returns the id to stream. */
    @PostMapping("/deploy")
    public DeployStartedResponse start(@RequestBody DeployRequest req) {
        return deployments.start(current.require(), req);
    }

    /** Live per-host / terminal output for a run. */
    @GetMapping(value = "/deploy/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        return deployments.stream(id);
    }

    @GetMapping("/deployments")
    public List<DeploymentView> history() {
        return deployments.history();
    }
}
