package com.example.abac.document.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /** 支持服务发现的 RestTemplate，供本服务向 PDP 提问。 */
    @Bean
    @LoadBalanced
    public RestTemplate lbRestTemplate() {
        return new RestTemplate();
    }
}
