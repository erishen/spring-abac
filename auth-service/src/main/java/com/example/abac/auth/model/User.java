package com.example.abac.auth.model;

import jakarta.persistence.*;

/**
 * 用户。除凭据外，还持有 ABAC 所需的<b>主体属性</b>：
 * department（部门）/ clearance（密级许可 1-5）/ region（所在地区）/ title（岗位）。
 * 这些属性在登录时被写进 JWT 的 attrs，成为 PDP 判定的输入。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** 部门：ENG / SALES / EXEC ... */
    private String department;

    /** 密级许可，1-5，数值越大可看越敏感的资源。 */
    private Integer clearance;

    /** 所在地区：CN / US ... */
    private String region;

    /** 岗位：admin / manager / engineer / sales ... */
    private String title;

    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Integer getClearance() {
        return clearance;
    }

    public void setClearance(Integer clearance) {
        this.clearance = clearance;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
