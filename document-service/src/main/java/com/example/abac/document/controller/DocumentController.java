package com.example.abac.document.controller;

import com.example.abac.document.dto.DocumentDtos.CreateDocumentRequest;
import com.example.abac.document.dto.DocumentDtos.DocumentDto;
import com.example.abac.document.dto.DocumentDtos.DocumentPage;
import com.example.abac.document.dto.DocumentDtos.UpdateDocumentRequest;
import com.example.abac.document.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档接口。主体属性由网关从 JWT 解析后放在 X-Attr-* 请求头里透传下来，
 * 本服务据此组装 subject 属性包再向 PDP 提问（网关是唯一入口，故可信任这些头）。
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/documents")
    public DocumentPage list(@RequestParam(defaultValue = "") String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestHeader(value = "X-User", required = false) String user,
                             @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                             @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                             @RequestHeader(value = "X-Attr-Region", required = false) String region,
                             @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        return documentService.list(q, page, size, subject(user, dept, clearance, region, title));
    }

    @GetMapping("/documents/{id}")
    public DocumentDto get(@PathVariable Long id,
                           @RequestHeader(value = "X-User", required = false) String user,
                           @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                           @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                           @RequestHeader(value = "X-Attr-Region", required = false) String region,
                           @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        return documentService.get(id, subject(user, dept, clearance, region, title));
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto create(@RequestBody CreateDocumentRequest req,
                              @RequestHeader(value = "X-User", required = false) String user,
                              @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                              @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                              @RequestHeader(value = "X-Attr-Region", required = false) String region,
                              @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        return documentService.create(req, subject(user, dept, clearance, region, title));
    }

    @PutMapping("/documents/{id}")
    public DocumentDto update(@PathVariable Long id, @RequestBody UpdateDocumentRequest req,
                              @RequestHeader(value = "X-User", required = false) String user,
                              @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                              @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                              @RequestHeader(value = "X-Attr-Region", required = false) String region,
                              @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        return documentService.update(id, req, subject(user, dept, clearance, region, title));
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id,
                       @RequestHeader(value = "X-User", required = false) String user,
                       @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                       @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                       @RequestHeader(value = "X-Attr-Region", required = false) String region,
                       @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        documentService.delete(id, subject(user, dept, clearance, region, title));
    }

    @PostMapping("/documents/{id}/publish")
    public DocumentDto publish(@PathVariable Long id,
                               @RequestHeader(value = "X-User", required = false) String user,
                               @RequestHeader(value = "X-Attr-Department", required = false) String dept,
                               @RequestHeader(value = "X-Attr-Clearance", required = false) String clearance,
                               @RequestHeader(value = "X-Attr-Region", required = false) String region,
                               @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        return documentService.publish(id, subject(user, dept, clearance, region, title));
    }

    /** 把 X-Attr-* 头还原成 PDP 需要的主体属性包。 */
    private Map<String, Object> subject(String user, String dept, String clearance,
                                        String region, String title) {
        Map<String, Object> s = new HashMap<>();
        s.put("username", user == null ? "anonymous" : user);
        s.put("department", dept);
        s.put("clearance", parseIntOr(clearance, 1));
        s.put("region", region);
        s.put("title", title);
        return s;
    }

    private static int parseIntOr(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
