package com.example.shortener.repository;

import com.example.shortener.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditEvent> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    Page<AuditEvent> findByActorOrderByOccurredAtDesc(String actor, Pageable pageable);
}
