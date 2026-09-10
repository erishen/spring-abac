package com.example.abac.document.controller;

import com.example.abac.document.dto.DocumentDtos.ResourceAttributes;
import com.example.abac.document.service.DocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PIP 端点：供 PDP 回源查询资源属性。
 *
 * 只走服务内部（网关不路由 /internal/**，外部访问不到），返回的是判定所需的属性而非业务全量，
 * 也不含任何凭据信息。abac-service 经服务发现直连这里。
 */
@RestController
@RequestMapping("/internal")
public class AttributesController {

    private final DocumentService documentService;

    public AttributesController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/attributes/{id}")
    public ResourceAttributes attributes(@PathVariable Long id) {
        Map<String, Object> attrs = documentService.attributes(id);
        return new ResourceAttributes(attrs);
    }
}
