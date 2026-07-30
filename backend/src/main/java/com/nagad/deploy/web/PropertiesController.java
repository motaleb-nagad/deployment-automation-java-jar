package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.PropertiesService;
import org.springframework.web.bind.annotation.*;

/**
 * The application-properties screen: surgical, per-host:app edits of
 * {@code application.properties} (preview / update / add_csv / remove_csv / rename /
 * append / insert / diff / restore). Backed by {@link PropertiesService}.
 */
@RestController
@RequestMapping("/api")
public class PropertiesController {

    private final PropertiesService properties;
    private final CurrentUser current;

    public PropertiesController(PropertiesService properties, CurrentUser current) {
        this.properties = properties;
        this.current = current;
    }

    /** Host/app tree (like Deploy) plus the operations the screen offers. */
    @GetMapping("/properties/catalog")
    public PropertiesCatalog catalog() {
        return properties.catalog();
    }

    /** Validate and run one properties edit; returns the console output + diff. */
    @PostMapping("/properties/apply")
    public PropertiesResult apply(@RequestBody PropertiesRequest req) {
        return properties.apply(current.require(), req);
    }
}
