package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.FleetView;
import com.nagad.deploy.service.FleetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetService fleet;

    public FleetController(FleetService fleet) {
        this.fleet = fleet;
    }

    /** scenario = incident (default) | healthy — the two demo states from the brief. */
    @GetMapping
    public FleetView fleet(@RequestParam(defaultValue = "incident") String scenario) {
        return fleet.view(scenario);
    }
}
