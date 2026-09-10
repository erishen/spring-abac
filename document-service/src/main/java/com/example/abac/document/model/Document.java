package com.example.abac.document.model;

import jakarta.persistence.*;

/**
 * 文档（业务域资源）。除业务字段外，还带有 ABAC 判定需要的<b>资源属性</b>：
 * owner（作者）/ department（归属部门）/ classification（密级）/ status（状态）。
 * 这些属性通过 /internal/attributes/{id} 暴露给 PDP 的 PIP 回源查询。
 */
@Entity
@Table(name = "documents")
public class Document {

    /** 密级 → 所需 clearance 的映射：PUBLIC=1 / INTERNAL=2 / CONFIDENTIAL=4 / SECRET=5。 */
    public static int requiredClearanceOf(String classification) {
        if (classification == null) {
            return 1;
        }
        return switch (classification.trim().toUpperCase()) {
            case "SECRET" -> 5;
            case "CONFIDENTIAL" -> 4;
            case "INTERNAL" -> 2;
            default -> 1; // PUBLIC
        };
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 4000)
    private String content;

    /** 作者（对应 auth 的 username）。 */
    @Column(nullable = false)
    private String owner;

    /** 归属部门：ENG / SALES / EXEC ... */
    @Column(nullable = false)
    private String department;

    /** 密级：PUBLIC / INTERNAL / CONFIDENTIAL / SECRET。 */
    @Column(nullable = false)
    private String classification;

    /** 状态：DRAFT / PUBLISHED / ARCHIVED。 */
    @Column(nullable = false)
    private String status;

    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
