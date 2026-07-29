package com.nagad.deploystg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone staging deployment service. Separate deployable/image from the main console
 * (deployment-automation-backend-stg), it serves only {@code /api/stg/**}: upload-driven
 * stop/deploy/start against the {@code stg-deployment} Ansible bundle. Authentication is
 * delegated to the main backend and it shares the main backend's Postgres.
 */
@SpringBootApplication
public class StgDeployApplication {
    public static void main(String[] args) {
        SpringApplication.run(StgDeployApplication.class, args);
    }
}
