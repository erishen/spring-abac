package com.example.abac.document;

import com.example.abac.document.service.DocumentService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

/** 文档服务：ABAC 保护的业务域（边缘 PEP + 服务内行级过滤双重闸门）。 */
@SpringBootApplication
@EnableDiscoveryClient
public class DocumentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(DocumentService documentService) {
        return args -> documentService.seedIfEmpty();
    }
}
