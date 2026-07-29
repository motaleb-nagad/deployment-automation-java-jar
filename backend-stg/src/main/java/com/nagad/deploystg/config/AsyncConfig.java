package com.nagad.deploystg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /** Staging runs stream for minutes — never on a request thread. One virtual thread per run. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService runExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
