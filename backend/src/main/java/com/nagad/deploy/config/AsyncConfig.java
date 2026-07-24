package com.nagad.deploy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /**
     * Deploy/fetch runs stream for minutes — never on a request thread. Java 21 virtual
     * threads make one-thread-per-run cheap even with many concurrent SSE sessions.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService runExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
