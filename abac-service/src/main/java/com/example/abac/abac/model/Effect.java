package com.example.abac.abac.model;

/** 策略效果：PERMIT 放行 / DENY 拒绝。裁决合并时 DENY 优先（deny-override）。 */
public enum Effect {
    PERMIT,
    DENY
}
