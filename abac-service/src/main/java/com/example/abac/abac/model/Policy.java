package com.example.abac.abac.model;

import jakarta.persistence.*;

/**
 * 一条 ABAC 策略。
 *
 * 语义：对于 resourceType 上的 action，若 condition（SpEL）在
 * 「主体/资源/环境」属性上下文里求值为 true，则产生 effect 的效果。
 * condition 留空 = 无条件命中。priority 数值越大越先判定。
 */
@Entity
@Table(name = "policies")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** 策略效果：PERMIT / DENY。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Effect effect;

    /** 资源类型：DOCUMENT / USER / POLICY / AUDIT，"*" 或不填表示全部。 */
    @Column(length = 40)
    private String resourceType;

    /** 动作：READ / CREATE / UPDATE / DELETE / PUBLISH，"*" 或不填表示全部。 */
    @Column(length = 20)
    private String action;

    /** SpEL 条件表达式，可用 subject.* / resource.* / env.* / action。留空即无条件。 */
    @Column(length = 1000)
    private String condition;

    /** 优先级，数值越大越先判定。 */
    private int priority;

    private boolean enabled = true;

    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Effect getEffect() {
        return effect;
    }

    public void setEffect(Effect effect) {
        this.effect = effect;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
