package com.nagad.deploy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Nagad Deploy Console — governed CD tooling.
 *
 * <p>A safer, auditable replacement for the {@code ./run.sh} Ansible wrapper: fetch a
 * jar from staging, gate it behind super-admin approval, then run stop/deploy/start
 * against production. Every action is written to an append-only audit trail.
 */
@SpringBootApplication
@EnableAsync
public class DeployConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeployConsoleApplication.class, args);
    }
}
