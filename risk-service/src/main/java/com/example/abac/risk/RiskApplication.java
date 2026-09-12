package com.example.abac.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RiskApplication {

    private static final Logger log = LoggerFactory.getLogger(RiskApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
        log.info("[risk] risk-service started (trade pre-check: PERMIT / DENY / REVIEW)");
    }
}
