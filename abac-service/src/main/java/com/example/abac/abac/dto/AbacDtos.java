package com.example.abac.abac.dto;

import java.util.Map;

/** ABAC 服务对外 DTO（record 简化）。 */
public final class AbacDtos {

    private AbacDtos() {
    }

    public record PolicyDto(Long id, String name, String description, String effect,
                            String resourceType, String action, String condition,
                            int priority, boolean enabled, Long createdAt) {
    }

    public record CreatePolicyRequest(String name, String description, String effect,
                                      String resourceType, String action, String condition,
                                      Integer priority, Boolean enabled) {
    }

    /** 更新策略：字段为 null 表示不改。 */
    public record UpdatePolicyRequest(String name, String description, String effect,
                                      String resourceType, String action, String condition,
                                      Integer priority, Boolean enabled) {
    }

    /**
     * 资源引用。attributes 已填全时 PDP 直接用；只给了 id 时由 PDP 经 PIP 回源查询。
     * type 取值：DOCUMENT / USER / POLICY / AUDIT。
     */
    public record ResourceRef(String type, String id, Map<String, Object> attributes) {
    }

    /** 一次裁决请求：主体属性 + 资源（引用或完整属性）+ 动作 + 环境属性。 */
    public record DecisionRequest(Map<String, Object> subject, ResourceRef resource,
                                  String action, Map<String, Object> environment) {
    }

    /** 批量裁决（业务服务做行级过滤时一次问多个资源）。 */
    public record BatchDecisionRequest(java.util.List<DecisionRequest> requests) {
    }
}
