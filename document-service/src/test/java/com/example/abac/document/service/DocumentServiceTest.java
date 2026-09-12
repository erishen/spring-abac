package com.example.abac.document.service;

import com.example.abac.document.dto.DocumentDtos.CreateDocumentRequest;
import com.example.abac.document.dto.DocumentDtos.DocumentDto;
import com.example.abac.document.dto.DocumentDtos.DocumentPage;
import com.example.abac.document.dto.DocumentDtos.UpdateDocumentRequest;
import com.example.abac.document.exception.ForbiddenException;
import com.example.abac.document.exception.NotFoundException;
import com.example.abac.document.model.Document;
import com.example.abac.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentService 行级过滤与对象级校验的单测。
 * 核心保护点：列表只返回 PDP 放行的行；单条操作 PDP 拒绝 / 不可用时一律 fail-closed 抛 403。
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;
    @Mock
    private AbacClient abacClient;

    private DocumentService service;

    private static final Map<String, Object> SUBJECT = Map.of(
            "username", "alice", "department", "ENG",
            "clearance", 7, "region", "CN", "title", "engineer");

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository, abacClient);
    }

    private static Document doc(Long id, String title, String owner, String dept,
                                String classification, String status) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setContent("content of " + title);
        d.setOwner(owner);
        d.setDepartment(dept);
        d.setClassification(classification);
        d.setStatus(status);
        d.setCreatedAt(1_000L);
        return d;
    }

    private static AbacClient.DecisionView permit() {
        return new AbacClient.DecisionView("PERMIT", true, 1L, "DOC-30", "ok");
    }

    private static AbacClient.DecisionView deny() {
        return new AbacClient.DecisionView("DENY", false, 2L, "DOC-90", "clearance too low");
    }

    // ---------- list：行级过滤 ----------

    @Test
    void list_filtersOutRowsThePdpDenies() {
        when(repository.findAll()).thenReturn(List.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED"),
                doc(2L, "B", "carol", "ENG", "CONFIDENTIAL", "DRAFT")));
        when(abacClient.decideBatch(anyList())).thenReturn(List.of(permit(), deny()));

        DocumentPage page = service.list(null, 1, 10, SUBJECT);

        assertEquals(1, page.totalElements());
        assertEquals(1, page.content().size());
        assertEquals("A", page.content().get(0).title());
        // 请求按文档顺序构造，一条文档一个裁决请求
        verify(abacClient).decideBatch(anyList());
    }

    @Test
    void list_withPdpReturningFewerDecisionsTreatsMissingAsInvisible() {
        when(repository.findAll()).thenReturn(List.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED"),
                doc(2L, "B", "carol", "ENG", "CONFIDENTIAL", "DRAFT")));
        // PDP 只回了 1 条（异常收缩），另一条按不可见处理，不允许越权显示
        when(abacClient.decideBatch(anyList())).thenReturn(List.of(permit()));

        DocumentPage page = service.list(null, 1, 10, SUBJECT);

        assertEquals(1, page.totalElements());
    }

    @Test
    void list_emptyRepositoryReturnsEmptyPage() {
        when(repository.findAll()).thenReturn(List.of());
        DocumentPage page = service.list(null, 1, 10, SUBJECT);
        assertEquals(0, page.totalElements());
        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalPages());
    }

    @Test
    void list_pageOutOfRangeClampsToLastPage() {
        when(repository.findAll()).thenReturn(List.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED"),
                doc(2L, "B", "alice", "ENG", "PUBLIC", "PUBLISHED")));
        when(abacClient.decideBatch(anyList())).thenReturn(List.of(permit(), permit()));

        DocumentPage page = service.list(null, 99, 1, SUBJECT);

        assertEquals(2, page.totalElements());
        assertEquals(1, page.number()); // 钳制到最后一页（第 2 页，index=1）
        assertEquals(1, page.content().size());
    }

    @Test
    void list_usesKeywordSearchWhenQueryPresent() {
        when(repository.search("薪酬")).thenReturn(List.of(
                doc(1L, "薪酬制度", "admin", "EXEC", "SECRET", "PUBLISHED")));
        when(abacClient.decideBatch(anyList())).thenReturn(List.of(permit()));

        DocumentPage page = service.list("薪酬", 1, 10, SUBJECT);

        assertEquals(1, page.totalElements());
        verify(repository, never()).findAll();
    }

    // ---------- 单条操作：对象级校验 ----------

    @Test
    void get_permittedReturnsDto() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED")));
        when(abacClient.decide(any(), any(), any())).thenReturn(permit());

        DocumentDto dto = service.get(1L, SUBJECT);

        assertEquals("A", dto.title());
        assertEquals(1, dto.requiredClearance()); // PUBLIC 密级
    }

    @Test
    void get_deniedThrowsForbidden() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "B", "carol", "ENG", "CONFIDENTIAL", "DRAFT")));
        when(abacClient.decide(any(), any(), any())).thenReturn(deny());

        assertThrows(ForbiddenException.class, () -> service.get(1L, SUBJECT));
    }

    @Test
    void get_missingDocumentThrowsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.get(404L, SUBJECT));
    }

    @Test
    void get_pdpUnavailableFailsClosed() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED")));
        when(abacClient.decide(any(), any(), any()))
                .thenThrow(new AbacClient.PdpUnavailableException("boom"));

        assertThrows(ForbiddenException.class, () -> service.get(1L, SUBJECT));
    }

    @Test
    void delete_deniedDoesNotTouchRepository() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "B", "carol", "ENG", "CONFIDENTIAL", "DRAFT")));
        when(abacClient.decide(any(), any(), any())).thenReturn(deny());

        assertThrows(ForbiddenException.class, () -> service.delete(1L, SUBJECT));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void delete_permittedDeletes() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED")));
        when(abacClient.decide(any(), any(), any())).thenReturn(permit());

        service.delete(1L, SUBJECT);

        verify(repository).deleteById(1L);
    }

    @Test
    void publish_deniedThrowsForbidden() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "DRAFT")));
        when(abacClient.decide(any(), any(), any())).thenReturn(deny());

        assertThrows(ForbiddenException.class, () -> service.publish(1L, SUBJECT));
    }

    // ---------- create：默认值与输入校验 ----------

    @Test
    void create_appliesDefaultsForOwnerDepartmentAndClassification() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentDto dto = service.create(
                new CreateDocumentRequest(" 薪酬制度 ", "content", null, null, null), SUBJECT);

        assertEquals("薪酬制度", dto.title()); // trim
        assertEquals("alice", dto.owner());   // 主体用户名
        assertEquals("ENG", dto.department()); // 主体部门
        assertEquals("INTERNAL", dto.classification()); // 默认密级
        assertEquals("DRAFT", dto.status());  // 默认状态
    }

    @Test
    void create_rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateDocumentRequest("  ", "c", null, null, null), SUBJECT));
    }

    @Test
    void create_rejectsUnknownClassification() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateDocumentRequest("T", "c", null, "TOP-SECRET", null), SUBJECT));
    }

    // ---------- update ----------

    @Test
    void update_deniedThrowsForbiddenAndDoesNotSave() {
        when(repository.findById(1L)).thenReturn(Optional.of(
                doc(1L, "A", "alice", "ENG", "PUBLIC", "PUBLISHED")));
        when(abacClient.decide(any(), any(), any())).thenReturn(deny());

        assertThrows(ForbiddenException.class, () -> service.update(
                1L, new UpdateDocumentRequest("new", null, null, null, null), SUBJECT));
        verify(repository, never()).save(any());
    }
}
