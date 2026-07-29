package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.StgDeploymentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The <strong>staging</strong> deployment channel — upload-driven stop/deploy/start against the
 * {@code stg-deployment} bundle. Served under {@code /api/stg}; the SPA exposes it at
 * {@code /stg-deployment}.
 */
@RestController
@RequestMapping("/api/stg")
public class StgDeploymentController {

    private final StgDeploymentService stg;
    private final CurrentUser current;

    public StgDeploymentController(StgDeploymentService stg, CurrentUser current) {
        this.stg = stg;
        this.current = current;
    }

    /** Staging groups (core/portal) with apps + jar names, the portal UIs, and the bundle path. */
    @GetMapping("/catalog")
    public StgCatalog catalog() {
        return stg.catalog();
    }

    /**
     * Stage a manually-uploaded file into the bundle on the jump host.
     * kind = jar | cfg | portalui; target = app key (jar/cfg) or UI name (portalui);
     * group = core | portal (required for jar so the jar_map name can be resolved).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StgUploadResponse upload(@RequestParam("kind") String kind,
                                    @RequestParam(value = "group", required = false) String group,
                                    @RequestParam("target") String target,
                                    @RequestPart("file") MultipartFile file) {
        return stg.upload(current.require(), kind, group, target, file);
    }

    /** Validate + register a staging jar/config run; returns the id + single-use stream ticket. */
    @PostMapping("/deploy")
    public DeployStartedResponse deploy(@RequestBody StgDeployRequest req) {
        return stg.startDeploy(current.require(), req);
    }

    /** Validate + register a staging portal-UI run; returns the id + single-use stream ticket. */
    @PostMapping("/portal-ui")
    public DeployStartedResponse portalUi(@RequestBody StgPortalUiRequest req) {
        return stg.startPortalUi(current.require(), req);
    }

    /**
     * Live output for a staging run. Authorised by the single-use stream ticket returned from
     * the start call (EventSource cannot send an Authorization header, so the bearer token must
     * never travel in this URL).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("ticket") String ticket) {
        return stg.stream(ticket);
    }
}
