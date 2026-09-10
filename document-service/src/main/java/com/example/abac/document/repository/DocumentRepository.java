package com.example.abac.document.repository;

import com.example.abac.document.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** 标题/内容/作者模糊检索（行级 ABAC 过滤在此结果之上再做）。 */
    @Query("select d from Document d where lower(d.title) like lower(concat('%', :q, '%'))"
            + " or lower(coalesce(d.content, '')) like lower(concat('%', :q, '%'))"
            + " or lower(d.owner) like lower(concat('%', :q, '%'))")
    List<Document> search(@Param("q") String q);
}
