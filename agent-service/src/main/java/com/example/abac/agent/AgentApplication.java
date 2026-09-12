package com.example.abac.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
        log.info("[agent] agent-service started (tool-call pre-check via ABAC PDP)");
    }
}
