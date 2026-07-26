package com.orderpilot.api.rest;

import com.orderpilot.api.dto.TrustAiProjectionDtos.ProcessTrustAiEventsResponse;
import com.orderpilot.api.dto.TrustAiProjectionDtos.ProjectionFailureDto;
import com.orderpilot.api.dto.TrustAiProjectionDtos.TrustAiDomainEventDto;
import com.orderpilot.api.dto.TrustAiProjectionDtos.TrustAiProjectionCheckpointDto;
import com.orderpilot.application.services.trust.ProjectionQueryService;
import com.orderpilot.application.services.trust.TrustAiProjectorRuntimeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiEventStatus;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpoint;
import com.orderpilot.domain.trust.events.TrustAiProjectionStatus;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Tenant-scoped projector control + observability under {@code /api/v1/trust/ai-events}. Reads require
 * {@code TRUST_AI_EVENT_READ}; processing requires the stronger {@code TRUST_AI_EVENT_PROCESS} (see
 * {@code ApiPermissionInterceptor}). Processing is explicit and tenant-scoped — there is no background
 * daemon. Tenant is resolved from context; event ids are never trusted across tenants. No raw payloads
 * are returned.
 */
@RestController
public class TrustAiProjectionController {
  private final TrustAiProjectorRuntimeService runtime;
  private final ProjectionQueryService queryService;

  public TrustAiProjectionController(
      TrustAiProjectorRuntimeService runtime, ProjectionQueryService queryService) {
    this.runtime = runtime;
    this.queryService = queryService;
  }

  @PostMapping("/api/v1/trust/ai-events/process")
  public ProcessTrustAiEventsResponse processBatch(
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return runtime.processTenantBatch(TenantContext.requireTenantId(), limit);
  }

  @PostMapping("/api/v1/trust/ai-events/{eventId}/process")
  public TrustAiDomainEventDto processOne(@PathVariable UUID eventId) {
    return toDto(runtime.processEvent(TenantContext.requireTenantId(), eventId));
  }

  @GetMapping("/api/v1/trust/ai-events")
  public List<TrustAiDomainEventDto> listEvents(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "eventType", required = false) String eventType,
      @RequestParam(name = "sourceType", required = false) String sourceType,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return queryService.listEvents(TenantContext.requireTenantId(),
        parseEnum(TrustAiEventStatus.class, status), parseEnum(TrustAiEventType.class, eventType),
        parseEnum(AiMemorySourceType.class, sourceType), page, size)
        .stream().map(TrustAiProjectionController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-events/checkpoints")
  public List<TrustAiProjectionCheckpointDto> listCheckpoints(
      @RequestParam(name = "projectorName", required = false) String projectorName,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return queryService.listCheckpoints(TenantContext.requireTenantId(), projectorName,
        parseEnum(TrustAiProjectionStatus.class, status), page, size)
        .stream().map(TrustAiProjectionController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-events/dead-letter")
  public List<ProjectionFailureDto> listDeadLetter(
      @RequestParam(name = "eventType", required = false) String eventType,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return queryService.listDeadLettered(TenantContext.requireTenantId(),
        parseEnum(TrustAiEventType.class, eventType), page, size)
        .stream().map(TrustAiProjectionController::toFailureDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-events/{eventId}")
  public TrustAiDomainEventDto getEvent(@PathVariable UUID eventId) {
    return toDto(queryService.getEvent(TenantContext.requireTenantId(), eventId));
  }

  // ----------------------------- mappers -----------------------------

  private static TrustAiDomainEventDto toDto(TrustAiDomainEvent e) {
    return new TrustAiDomainEventDto(
        e.getId(), e.getEventType().name(), e.getSourceType().name(), e.getSourceId(), e.getSubjectType(),
        e.getSubjectId(), e.getStatus().name(), e.getPayloadVersion(),
        e.getPayloadSummary(), e.getOccurredAt(), e.getCreatedAt(), e.getProcessedAt(), e.getFailedAt(),
        e.getFailureCode(), e.getFailureMessage(), e.getRetryCount(), e.getNextRetryAt());
  }

  private static TrustAiProjectionCheckpointDto toDto(TrustAiProjectionCheckpoint c) {
    return new TrustAiProjectionCheckpointDto(
        c.getId(), c.getProjectorName(), c.getEventId(), c.getEventType().name(), c.getSourceType().name(),
        c.getSourceId(), c.getStatus().name(), c.getProjectedRecordType(), c.getProjectedRecordId(),
        c.getAttemptCount(), c.getStartedAt(), c.getCompletedAt(), c.getFailedAt(), c.getFailureCode(),
        c.getFailureMessage(), c.getUpdatedAt());
  }

  private static ProjectionFailureDto toFailureDto(TrustAiDomainEvent e) {
    return new ProjectionFailureDto(
        e.getId(), e.getEventType().name(), e.getSourceType().name(), e.getSourceId(), e.getStatus().name(),
        e.getFailureCode(), e.getFailureMessage(), e.getRetryCount(), e.getFailedAt());
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
    }
  }
}
