package com.example.abac.document.service;

import com.example.abac.document.dto.DocumentDtos.CreateDocumentRequest;
import com.example.abac.document.dto.DocumentDtos.DocumentDto;
import com.example.abac.document.dto.DocumentDtos.DocumentPage;
import com.example.abac.document.dto.DocumentDtos.UpdateDocumentRequest;
import com.example.abac.document.exception.ForbiddenException;
import com.example.abac.document.exception.NotFoundException;
import com.example.abac.document.model.Document;
import com.example.abac.document.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档业务域。
 *
 * 边缘有一道 PEP（网关）已按 URL 裁过一次，这里还会<b>按完整资源属性再裁一次</b>：
 * 列表接口做行级过滤（只返回当前主体读得到的行），单条操作做对象级校验。
 * 两道闸门问的是同一个 PDP，因此策略改动立刻同时生效，不会出现"列表看不见但能直连打开"的洞。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository repository;
    private final AbacClient abacClient;

    public DocumentService(DocumentRepository repository, AbacClient abacClient) {
        this.repository = repository;
        this.abacClient = abacClient;
    }

    /**
     * 列表：先按策略过滤出可见行，再分页。
     * 演示数据量小，直接全量取出后过滤；真实场景应把属性条件下推到 SQL。
     */
    public DocumentPage list(String q, int page, int size, Map<String, Object> subject) {
        List<Document> all = (q == null || q.isBlank())
                ? repository.findAll() : repository.search(q.trim());

        List<Map<String, Object>> requests = new ArrayList<>(all.size());
        for (Document d : all) {
            requests.add(decisionRequest(subject, d, "READ"));
        }
        List<AbacClient.DecisionView> decisions = abacClient.decideBatch(requests);

        List<DocumentDto> visible = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            AbacClient.DecisionView d =
                    i < decisions.size() ? decisions.get(i) : null;
            if (d != null && d.permitted()) {
                visible.add(toDto(all.get(i)));
            }
        }

        int total = visible.size();
        int pageSize = Math.max(size, 1);
        int pages = (int) Math.ceil((double) total / pageSize);
        int index = Math.min(Math.max(page, 0), Math.max(pages - 1, 0));
        int from = Math.min(index * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<DocumentDto> content = (total == 0) ? List.of() : visible.subList(from, to);
        return new DocumentPage(content, total, index, pageSize, pages);
    }

    public DocumentDto get(Long id, Map<String, Object> subject) {
        Document d = require(id);
        requirePermitted(subject, d, "READ");
        return toDto(d);
    }

    @Transactional
    public DocumentDto create(CreateDocumentRequest req, Map<String, Object> subject) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title required");
        }
        Document d = new Document();
        d.setTitle(req.title().trim());
        d.setContent(req.content());
        // 作者取当前主体；部门默认取主体的部门，密级默认 INTERNAL
        d.setOwner(ownerOf(subject));
        d.setDepartment(defaultStr(req.department(), strOr(subject.get("department"), "ENG")));
        d.setClassification(normalizeClassification(req.classification()));
        d.setStatus(normalizeStatus(req.status()));
        d.setCreatedAt(System.currentTimeMillis());
        return toDto(repository.save(d));
    }

    @Transactional
    public DocumentDto update(Long id, UpdateDocumentRequest req, Map<String, Object> subject) {
        Document d = require(id);
        requirePermitted(subject, d, "UPDATE");
        if (req.title() != null && !req.title().isBlank()) {
            d.setTitle(req.title().trim());
        }
        if (req.content() != null) {
            d.setContent(req.content());
        }
        if (req.department() != null) {
            d.setDepartment(req.department());
        }
        if (req.classification() != null) {
            d.setClassification(normalizeClassification(req.classification()));
        }
        if (req.status() != null) {
            d.setStatus(normalizeStatus(req.status()));
        }
        return toDto(repository.save(d));
    }

    @Transactional
    public void delete(Long id, Map<String, Object> subject) {
        Document d = require(id);
        requirePermitted(subject, d, "DELETE");
        repository.deleteById(id);
    }

    /** 发布：动作收敛到 manager/admin 由策略 P-15 控制，服务内只负责改状态。 */
    @Transactional
    public DocumentDto publish(Long id, Map<String, Object> subject) {
        Document d = require(id);
        requirePermitted(subject, d, "PUBLISH");
        d.setStatus("PUBLISHED");
        return toDto(repository.save(d));
    }

    /** PIP 回源用的资源属性（PDP 经 /internal/attributes/{id} 拉取）。 */
    public Map<String, Object> attributes(Long id) {
        return attributesOf(require(id));
    }

    // ---------------- 内部 ----------------

    /** 对象级校验：PDP 说不行就抛 403（PDP 本身不可用也按拒绝处理）。 */
    private void requirePermitted(Map<String, Object> subject, Document d, String action) {
        AbacClient.DecisionView decision;
        try {
            decision = abacClient.decide(subject, attributesOf(d), action);
        } catch (AbacClient.PdpUnavailableException e) {
            throw new ForbiddenException("PDP 不可用，拒绝执行（fail-closed）");
        }
        if (decision == null || !decision.permitted()) {
            throw new ForbiddenException(action + " 被策略拒绝: "
                    + (decision == null ? "no decision" : decision.reason()));
        }
    }

    private Map<String, Object> decisionRequest(Map<String, Object> subject, Document d,
                                                String action) {
        Map<String, Object> req = new HashMap<>();
        req.put("subject", subject);
        Map<String, Object> resource = new HashMap<>();
        resource.put("type", "DOCUMENT");
        resource.put("id", String.valueOf(d.getId()));
        resource.put("attributes", attributesOf(d));
        req.put("resource", resource);
        req.put("action", action);
        return req;
    }

    private Map<String, Object> attributesOf(Document d) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("type", "DOCUMENT");
        attrs.put("id", String.valueOf(d.getId()));
        attrs.put("title", d.getTitle());
        attrs.put("owner", d.getOwner());
        attrs.put("department", d.getDepartment());
        attrs.put("classification", d.getClassification());
        attrs.put("requiredClearance", Document.requiredClearanceOf(d.getClassification()));
        attrs.put("status", d.getStatus());
        return attrs;
    }

    private Document require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("document not found: " + id));
    }

    private static String ownerOf(Map<String, Object> subject) {
        return strOr(subject.get("username"), "unknown");
    }

    private static String normalizeClassification(String v) {
        if (v == null || v.isBlank()) {
            return "INTERNAL";
        }
        String c = v.trim().toUpperCase();
        return switch (c) {
            case "PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET" -> c;
            default -> throw new IllegalArgumentException(
                    "classification 只能是 PUBLIC/INTERNAL/CONFIDENTIAL/SECRET: " + v);
        };
    }

    private static String normalizeStatus(String v) {
        if (v == null || v.isBlank()) {
            return "DRAFT";
        }
        String s = v.trim().toUpperCase();
        return switch (s) {
            case "DRAFT", "PUBLISHED", "ARCHIVED" -> s;
            default -> throw new IllegalArgumentException(
                    "status 只能是 DRAFT/PUBLISHED/ARCHIVED: " + v);
        };
    }

    private static String defaultStr(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.trim().toUpperCase();
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static DocumentDto toDto(Document d) {
        return new DocumentDto(d.getId(), d.getTitle(), d.getContent(), d.getOwner(),
                d.getDepartment(), d.getClassification(), d.getStatus(),
                Document.requiredClearanceOf(d.getClassification()), d.getCreatedAt());
    }

    /** 幂等播种：覆盖 公开/内部/机密/绝密 × 不同部门/作者 的组合，便于演示差异裁决。 */
    @Transactional
    public void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        save("2026 薪酬制度（绝密）", "全员薪酬带宽与调薪规则", "admin", "EXEC", "SECRET", "PUBLISHED");
        save("B 轮融资计划书（机密）", "估值、条款与投资人清单", "carol", "ENG", "CONFIDENTIAL", "DRAFT");
        save("Q3 部门 OKR（内部）", "工程部三季度目标与关键结果", "carol", "ENG", "INTERNAL", "DRAFT");
        save("接口设计规范（内部）", "REST 命名、错误码与分页约定", "alice", "ENG", "INTERNAL", "PUBLISHED");
        save("销售话术手册（内部）", "客户分层与异议处理话术", "bob", "SALES", "INTERNAL", "DRAFT");
        save("产品使用手册（公开）", "对外发布的产品说明", "alice", "ENG", "PUBLIC", "PUBLISHED");
        log.info("[document] seeded {} sample documents", repository.count());
    }

    private void save(String title, String content, String owner, String dept,
                      String classification, String status) {
        Document d = new Document();
        d.setTitle(title);
        d.setContent(content);
        d.setOwner(owner);
        d.setDepartment(dept);
        d.setClassification(classification);
        d.setStatus(status);
        d.setCreatedAt(System.currentTimeMillis());
        repository.save(d);
    }
}
