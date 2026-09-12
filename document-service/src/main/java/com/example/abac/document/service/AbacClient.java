package com.example.abac.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDP 客户端：本服务（文档域）做<b>行级 ABAC</b> 时向 abac-service 提问。
 *
 * 与网关 PEP 的区别：网关只有 URL 里的资源 id，属性要靠 PIP 回源；
 * 本服务自己持有文档的完整属性，直接随请求带过去，省一次回源。
 * PDP 不可用（网络异常/熔断）时抛 PdpUnavailableException，调用方按 fail-closed 处理。
 */
@Component
public class AbacClient {

    private static final Logger log = LoggerFactory.getLogger(AbacClient.class);

    private final RestTemplate lbRestTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public AbacClient(RestTemplate lbRestTemplate) {
        this.lbRestTemplate = lbRestTemplate;
    }

    /** 裁决结果视图（只取本服务用得上的字段，不引入 PDP 的内部类型）。 */
    public record DecisionView(String effect, boolean permitted, Long policyId,
                               String policyName, String reason) {
    }

    public DecisionView decide(Map<String, Object> subject, Map<String, Object> resource,
                               String action) {
        Map<String, Object> body = new HashMap<>();
        body.put("subject", subject);
        body.put("resource", resource);
        body.put("action", action);
        try {
            Map<?, ?> resp = lbRestTemplate.postForObject(
                    "http://abac-service/api/decide", body, Map.class);
            return toView(resp);
        } catch (Exception e) {
            log.warn("PDP 调用失败: {}", e.getMessage());
            throw new PdpUnavailableException("PDP unavailable: " + e.getMessage());
        }
    }

    /** 一次问多个资源（行级过滤用），返回与请求同序的结果。 */
    public List<DecisionView> decideBatch(List<Map<String, Object>> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of("requests", requests);
        try {
            List<?> resp = lbRestTemplate.postForObject(
                    "http://abac-service/api/decide/batch", body, List.class);
            List<DecisionView> out = new ArrayList<>(requests.size());
            if (resp != null) {
                for (Object o : resp) {
                    out.add(toView(o instanceof Map<?, ?> m ? m : Map.of()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("PDP 批量调用失败: {}", e.getMessage());
            throw new PdpUnavailableException("PDP unavailable: " + e.getMessage());
        }
    }

    private DecisionView toView(Map<?, ?> m) {
        if (m == null || m.isEmpty()) {
            return new DecisionView("DENY", false, null, null, "empty PDP response");
        }
        Object permitted = m.get("permitted");
        Object policyId = m.get("policyId");
        return new DecisionView(
                str(m.get("effect")),
                Boolean.TRUE.equals(permitted),
                policyId == null ? null : Long.valueOf(String.valueOf(policyId)),
                str(m.get("policyName")),
                str(m.get("reason")));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** PDP 不可用。文档服务对此一律 fail-closed（拒绝而不是放行）。 */
    public static class PdpUnavailableException extends RuntimeException {

        public PdpUnavailableException(String message) {
            super(message);
        }
    }
}
