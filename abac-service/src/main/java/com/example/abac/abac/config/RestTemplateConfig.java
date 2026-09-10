package com.example.abac.abac.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /** 支持服务发现（服务名直连）的 RestTemplate，供 PIP 回源查询资源属性。 */
    @Bean
    @LoadBalanced
    public RestTemplate lbRestTemplate() {
        return new RestTemplate();
    }
}
