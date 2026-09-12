package com.example.abac.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * 带服务发现能力的 WebClient.Builder。
     * 设响应超时（兜底 resilience4j 熔断）：PDP / 审计调用超过 1.5s 直接失败，
     * 交由 AuthGlobalFilter 的 onErrorResume 走 fail-closed（拒绝而非放行）。
     * 显式命名（lbWebClientBuilder）是为了和 Spring 容器里的其他 Builder 区分开：
     * 网关过滤器只认这一个，避免被别处注入的普通 Builder 顶掉导致 lb:// 无法解析。
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder lbWebClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(1500));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
