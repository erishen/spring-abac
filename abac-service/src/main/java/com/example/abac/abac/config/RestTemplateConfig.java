package com.example.abac.abac.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /**
     * 支持服务发现（服务名直连）的 RestTemplate，供 PIP 回源查询资源属性。
     * 显式设连接/读取超时，避免 document-service 卡住时 PDP 线程永久阻塞。
     */
    @Bean
    @LoadBalanced
    public RestTemplate lbRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(800));
        factory.setReadTimeout(Duration.ofMillis(1500));
        return new RestTemplate(factory);
    }
}
