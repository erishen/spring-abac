package com.example.abac.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/** 审计服务：append-only 记录网关 PEP 的每一次裁决（含命中策略）。 */
@SpringBootApplication
@EnableDiscoveryClient
public class AuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
