package com.example.abac.abac.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PIP（Policy Information Point）：按资源类型回源查询资源属性。
 *
 * PDP 自己不存业务数据。网关 PEP 只认得 URL 里的资源 id，属性得由 PIP 补齐；
 * 而业务服务（document-service）本身持有完整属性，会直接随请求带过来，不走这里。
 * 目前只为 DOCUMENT 类型接通回源，其余类型返回空属性（由调用方按 fail-closed 处理）。
 */
@Component
public class PipClient {

    private static final Logger log = LoggerFactory.getLogger(PipClient.class);

    private final RestTemplate lbRestTemplate;

    public PipClient(RestTemplate lbRestTemplate) {
        this.lbRestTemplate = lbRestTemplate;
    }

    /** 查询资源属性；查不到或不可用抛 PipException，由调用方决定 fail-closed / fail-open。 */
    public Map<String, Object> fetch(String resourceType, String id) {
        if (id == null || id.isBlank()) {
            return Map.of();
        }
        if (!"DOCUMENT".equalsIgnoreCase(resourceType)) {
            return Map.of();
        }
        try {
            String url = "http://document-service/internal/attributes/{id}";
            Map<?, ?> resp = lbRestTemplate.getForObject(url, Map.class, id);
            if (resp == null) {
                throw new PipException("PIP returned empty body for DOCUMENT#" + id);
            }
            Map<String, Object> attrs = new HashMap<>();
            resp.forEach((k, v) -> {
                if (k != null) {
                    attrs.put(String.valueOf(k), v);
                }
            });
            log.debug("PIP fetched DOCUMENT#{} -> {}", id, attrs.keySet());
            return attrs;
        } catch (PipException e) {
            throw e; // 内部已带具体原因（如空响应），直接透传，避免被下面再包一层 "PIP lookup failed"
        } catch (Exception e) {
            throw new PipException("PIP lookup failed for DOCUMENT#" + id + ": " + e.getMessage());
        }
    }

    /** PIP 不可用（业务服务宕机 / 资源不存在 / 网络异常）。 */
    public static class PipException extends RuntimeException {

        public PipException(String message) {
            super(message);
        }
    }
}
