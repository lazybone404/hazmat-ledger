package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findBySourceCode(String sourceCode);

    List<KnowledgeDocument> findAllByOrderByDocTypeAscSourceCodeAsc();

    List<KnowledgeDocument> findByDocTypeOrderBySourceCodeAsc(String docType);
}
