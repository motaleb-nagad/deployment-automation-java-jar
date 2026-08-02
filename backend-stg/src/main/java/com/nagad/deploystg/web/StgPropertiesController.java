package com.nagad.deploystg.web;

import com.nagad.deploystg.dto.Dtos.*;
import com.nagad.deploystg.security.CurrentUser;
import com.nagad.deploystg.service.StgPropertiesService;
import org.springframework.web.bind.annotation.*;

/**
 * Staging application-properties API — surgical, per-app edits of {@code application.properties}
 * on a group's staging host. Served under {@code /api/stg/properties}; the SPA reaches it from
 * the STG DEPLOYMENT tab.
 */
@RestController
@RequestMapping("/api/stg/properties")
public class StgPropertiesController {

    private final StgPropertiesService properties;
    private final CurrentUser current;

    public StgPropertiesController(StgPropertiesService properties, CurrentUser current) {
        this.properties = properties;
        this.current = current;
    }

    @GetMapping("/catalog")
    public StgPropertiesCatalog catalog() {
        return properties.catalog();
    }

    @PostMapping("/apply")
    public StgPropertiesResult apply(@RequestBody StgPropertiesRequest req) {
        return properties.apply(current.require(), req);
    }
}
