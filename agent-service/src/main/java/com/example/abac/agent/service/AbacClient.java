package com.example.abac.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** Agent 校验服务的 PDP 客户端：把主体/资源/动作交给 abac-service 的 /api/decide 裁决。 */
@Component
public class AbacClient {

    private static final Logger log = LoggerFactory.getLogger(AbacClient.class);

    private final RestTemplate lbRestTemplate;

    public AbacClient(RestTemplate lbRestTemplate) {
        this.lbRestTemplate = lbRestTemplate;
    }

    /** PDP 返回的裁决视图。trace 保留逐条策略求值轨迹，供 UI 解释"为什么"。 */
    public record Decision(String effect, boolean permitted, Long policyId,
                           String policyName, String reason, List<Map<String, Object>> trace) {
    }

    public Decision decide(Map<String, Object> subject, Map<String, Object> resource, String action) {
        Map<String, Object> subj = subject == null ? Map.of() : subject;
        Map<String, Object> res = resource == null ? Map.of() : resource;
        Map<String, Object> body = Map.of(
                "subject", subj,
                "resource", res,
                "action", action == null ? "" : action);
        Map<?, ?> raw;
        try {
            raw = lbRestTemplate.postForObject(
                    "http://abac-service/api/decide", body, Map.class);
        } catch (Exception e) {
            // PDP 不可达时 fail-closed：Agent 工具调用不允许"裁决不了就放行"。
            throw new PdpUnavailableException("PDP unavailable: " + e.getMessage());
        }
        if (raw == null) {
            throw new PdpUnavailableException("PDP returned empty response");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) raw;
        String effect = String.valueOf(resp.getOrDefault("effect", "DENY"));
        boolean permitted = Boolean.TRUE.equals(resp.get("permitted"));
        Long policyId = resp.get("policyId") instanceof Number n ? n.longValue() : null;
        String policyName = resp.get("policyName") == null ? null : String.valueOf(resp.get("policyName"));
        String reason = resp.get("reason") == null ? null : String.valueOf(resp.get("reason"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = resp.get("trace") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        log.debug("PDP decide {} {} {} -> {} ({})", subj.get("agentId"),
                res.get("type"), action, effect, policyName);
        return new Decision(effect, permitted, policyId, policyName, reason, trace);
    }

    /** PDP 不可达/异常：一律 fail-closed（宁可拒绝，不可误放行）。 */
    public static class PdpUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;
        public PdpUnavailableException(String message) {
            super(message);
        }
    }
}
