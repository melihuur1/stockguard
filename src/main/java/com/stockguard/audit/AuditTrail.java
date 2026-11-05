package com.stockguard.audit;

import com.stockguard.common.config.StockGuardProperties;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the audit trail. MANDATORY propagation makes the event part of the business transaction: it commits
 * together with the change it describes or not at all, so the trail can neither miss a change nor invent one.
 */
@Service
public class AuditTrail {

    private final AuditEventRepository events;
    private final JsonMapper json;
    private final String instanceId;

    public AuditTrail(AuditEventRepository events, JsonMapper json, StockGuardProperties properties) {
        this.events = events;
        this.json = json;
        this.instanceId = properties.instanceId();
    }

    /** @param actorId the responsible user, or null when the system itself acted */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEventType type, UUID actorId, String entityType, UUID entityId, Map<String, ?> details) {
        events.save(new AuditEvent(type, actorId, instanceId, entityType, entityId, json.writeValueAsString(details)));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(UUID entityId, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return entityId == null ? events.findAllBy(request) : events.findByEntityId(entityId, request);
    }
}
