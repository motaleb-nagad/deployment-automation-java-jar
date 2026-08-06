package com.nagad.deploystg.web;

import com.nagad.deploystg.dto.Dtos.*;
import com.nagad.deploystg.security.CurrentUser;
import com.nagad.deploystg.service.StgDeploymentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The staging deployment API — upload-driven stop/deploy/start against the {@code stg-deployment}
 * bundle. Served under {@code /api/stg} by this standalone service; the SPA reaches it at
 * {@code /stg-deployment} (nginx routes {@code /api/stg} here).
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

    @GetMapping("/catalog")
    public StgCatalog catalog() {
        return stg.catalog();
    }

    /** Deployed-hash board: each service in a group with the git hash of its live staging jar. */
    @GetMapping("/hashes")
    public java.util.List<StgServiceHash> hashes(@RequestParam("group") String group) {
        return stg.serviceHashes(group);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StgUploadResponse upload(@RequestParam("kind") String kind,
                                    @RequestParam(value = "group", required = false) String group,
                                    @RequestParam("target") String target,
                                    @RequestPart("file") MultipartFile file) {
        return stg.upload(current.require(), kind, group, target, file);
    }

    @PostMapping("/deploy")
    public DeployStartedResponse deploy(@RequestBody StgDeployRequest req) {
        return stg.startDeploy(current.require(), req);
    }

    @PostMapping("/portal-ui")
    public DeployStartedResponse portalUi(@RequestBody StgPortalUiRequest req) {
        return stg.startPortalUi(current.require(), req);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("ticket") String ticket) {
        return stg.stream(ticket);
    }

    /** Staging history — runs, portal-UI deploys and property edits done in staging. */
    @GetMapping("/deployments")
    public java.util.List<StgHistoryRow> history() {
        return stg.history();
    }
}
