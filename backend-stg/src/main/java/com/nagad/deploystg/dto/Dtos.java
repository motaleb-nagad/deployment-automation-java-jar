package com.nagad.deploystg.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Wire DTOs for the staging deploy API. */
public final class Dtos {
    private Dtos() {}

    /** A staging service the operator can deploy: app key + the jar file the playbook expects. */
    public record StgAppView(String key, String jar) {}

    /** A staging group (core | portal) with its single host and the apps it deploys. */
    public record StgGroupView(String key, String label, String host, String ip, List<StgAppView> apps) {}

    /** What the STG DEPLOYMENT screen needs to build a run: the two groups, the portal UIs,
     *  and where the wrapper lives on the jump host. */
    public record StgCatalog(List<StgGroupView> groups, List<String> uis, String workingDir) {}

    /** Result of staging a manually-uploaded file on the jump host. {@code storedName} is the
     *  name the playbook will look for (jar_map name / {@code <app>-application.properties} /
     *  {@code <ui>.tar}); {@code targetPath} is where it landed under the bundle. */
    public record StgUploadResponse(String kind, String target, String storedName,
                                    String targetPath, long size, String sha256) {}

    /** Staging jar/config deploy — {@code ./run.sh <group> all <apps> <actions>} on the stg host. */
    public record StgDeployRequest(String group, List<String> apps, List<String> actions) {}

    /** Staging portal-UI deploy — {@code portalui/run.sh <uis> [date]}. */
    public record StgPortalUiRequest(List<String> uis, String date) {}

    /** streamTicket is a single-use, short-lived credential for opening the SSE stream. */
    public record DeployStartedResponse(String deploymentId, String streamTicket) {}

    /** The subset of the main backend's /api/auth/me response this service needs to authorise
     *  a caller (delegated auth). Unknown fields are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeResponse(String username, String role, boolean r, boolean w, boolean x,
                             boolean mustChangePassword) {}
}
