package com.example.abac.abac;

import com.example.abac.abac.service.AbacService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

/** ABAC PDP 服务：策略存储 + 裁决。启动即播种一组演示策略。 */
@SpringBootApplication
@EnableDiscoveryClient
public class AbacApplication {

    public static void main(String[] args) {
        SpringApplication.run(AbacApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(AbacService abacService) {
        return args -> abacService.seedIfEmpty();
    }
}
