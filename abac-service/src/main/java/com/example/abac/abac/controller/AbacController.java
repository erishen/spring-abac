package com.example.abac.abac.controller;

import com.example.abac.abac.dto.AbacDtos.BatchDecisionRequest;
import com.example.abac.abac.dto.AbacDtos.CreatePolicyRequest;
import com.example.abac.abac.dto.AbacDtos.DecisionRequest;
import com.example.abac.abac.dto.AbacDtos.PolicyDto;
import com.example.abac.abac.dto.AbacDtos.UpdatePolicyRequest;
import com.example.abac.abac.engine.PolicyEngine;
import com.example.abac.abac.service.AbacService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ABAC PDP 对外接口。
 * 谁能调用这些端点由网关 PEP 按策略裁决（策略域 POLICY 的读需要 manager/admin，
 * 写需要 admin），本服务只负责"答得对"，不关心"谁在问"。
 */
@RestController
@RequestMapping("/api")
public class AbacController {

    private final AbacService abacService;

    public AbacController(AbacService abacService) {
        this.abacService = abacService;
    }

    // ---------------- 策略管理 ----------------

    @GetMapping("/policies")
    public List<PolicyDto> listPolicies() {
        return abacService.listPolicies();
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyDto createPolicy(@RequestBody CreatePolicyRequest req) {
        return abacService.createPolicy(req);
    }

    @GetMapping("/policies/{id}")
    public PolicyDto getPolicy(@PathVariable Long id) {
        return abacService.getPolicy(id);
    }

    @PutMapping("/policies/{id}")
    public PolicyDto updatePolicy(@PathVariable Long id, @RequestBody UpdatePolicyRequest req) {
        return abacService.updatePolicy(id, req);
    }

    @DeleteMapping("/policies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePolicy(@PathVariable Long id) {
        abacService.deletePolicy(id);
    }

    // ---------------- 裁决 ----------------

    /**
     * 单次裁决。请求体带 subject / resource / action / environment，
     * 返回 PERMIT / DENY + 命中的策略 + 逐条策略的求值轨迹。
     */
    @PostMapping("/decide")
    public PolicyEngine.Decision decide(@RequestBody DecisionRequest req) {
        return abacService.decide(req);
    }

    /** 批量裁决（业务服务做行级过滤用），返回与请求同序的裁决列表。 */
    @PostMapping("/decide/batch")
    public List<PolicyEngine.Decision> decideBatch(@RequestBody BatchDecisionRequest req) {
        return abacService.decideBatch(req == null ? null : req.requests());
    }
}
