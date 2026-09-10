package com.example.abac.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * 带服务发现能力的 WebClient.Builder。
     * 显式命名（lbWebClientBuilder）是为了和 Spring 容器里的其他 Builder 区分开：
     * 网关过滤器只认这一个，避免被别处注入的普通 Builder 顶掉导致 lb:// 无法解析。
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder lbWebClientBuilder() {
        return WebClient.builder();
    }
}
