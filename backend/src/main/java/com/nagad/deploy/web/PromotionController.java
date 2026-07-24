package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.PromotionService;
import com.nagad.deploy.service.StagingCatalog;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private final PromotionService promotions;
    private final StagingCatalog staging;
    private final CurrentUser current;

    public PromotionController(PromotionService promotions, StagingCatalog staging, CurrentUser current) {
        this.promotions = promotions;
        this.staging = staging;
        this.current = current;
    }

    /** Staging sources and the built jars available on each. */
    @GetMapping("/staging")
    public List<StagingCatalog.Source> staging() {
        return staging.sources();
    }

    @PostMapping("/fetch")
    public List<PromotionView> fetch(@RequestBody FetchRequest req) {
        return promotions.fetch(current.require(), req);
    }

    @GetMapping("/mine")
    public List<PromotionView> mine() {
        return promotions.mine(current.require().getUsername());
    }

    @GetMapping
    public List<PromotionView> all() {
        return promotions.all();
    }
}
