package com.stockguard.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.stockguard.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
public class AuditController {

    private final AuditTrail audit;

    public AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(operationId = "searchAuditEvents", summary = "Browse the audit trail, newest first (admin)")
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(audit.search(entityId, page, size), AuditEventResponse::from);
    }

    public record AuditEventResponse(
            Long id,
            AuditEventType type,
            UUID actorId,
            String instanceId,
            String entityType,
            UUID entityId,
            @JsonRawValue @Schema(type = "object") String details,
            Instant createdAt) {

        static AuditEventResponse from(AuditEvent event) {
            return new AuditEventResponse(
                    event.getId(),
                    event.getEventType(),
                    event.getActorId(),
                    event.getInstanceId(),
                    event.getEntityType(),
                    event.getEntityId(),
                    event.getDetails(),
                    event.getCreatedAt());
        }
    }
}
