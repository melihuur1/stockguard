package com.stockguard.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    Page<AuditEvent> findAllBy(Pageable pageable);

    Page<AuditEvent> findByEntityId(UUID entityId, Pageable pageable);
}
