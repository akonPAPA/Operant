package com.orderpilot.application.services.trust;

import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiDomainEventRepository;
import com.orderpilot.domain.trust.events.TrustAiEventStatus;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpoint;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpointRepository;
import com.orderpilot.domain.trust.events.TrustAiProjectionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime — read-only observability.
 *
 * Bounded, tenant-scoped read APIs over events, projection checkpoints, and the dead-letter set
 * (modelled as events with status {@code DEAD_LETTERED}). Every finder is tenant-isolated and
 * limit-clamped — never an unbounded or cross-tenant scan.
 */
@Service
public class ProjectionQueryService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;

  private final TrustAiDomainEventRepository events;
  private final TrustAiProjectionCheckpointRepository checkpoints;

  public ProjectionQueryService(
      TrustAiDomainEventRepository events, TrustAiProjectionCheckpointRepository checkpoints) {
    this.events = events;
    this.checkpoints = checkpoints;
  }

  @Transactional(readOnly = true)
  public TrustAiDomainEvent getEvent(UUID tenantId, UUID eventId) {
    return events.findByIdAndTenantId(required(eventId, "eventId"), required(tenantId, "tenantId"))
        .orElseThrow(() -> new NotFoundException("Trust AI domain event not found"));
  }

  @Transactional(readOnly = true)
  public List<TrustAiDomainEvent> listEvents(UUID tenantId, TrustAiEventStatus status,
      TrustAiEventType eventType, AiMemorySourceType sourceType, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    if (status != null && eventType != null) {
      return events.findByTenantIdAndStatusAndEventTypeOrderByOccurredAtDesc(tenantId, status, eventType, pageable);
    }
    if (status != null) {
      return events.findByTenantIdAndStatusOrderByOccurredAtDesc(tenantId, status, pageable);
    }
    if (eventType != null) {
      return events.findByTenantIdAndEventTypeOrderByOccurredAtDesc(tenantId, eventType, pageable);
    }
    if (sourceType != null) {
      return events.findByTenantIdAndSourceTypeOrderByOccurredAtDesc(tenantId, sourceType, pageable);
    }
    return events.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
  }

  @Transactional(readOnly = true)
  public List<TrustAiDomainEvent> listDeadLettered(UUID tenantId, TrustAiEventType eventType, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    if (eventType != null) {
      return events.findByTenantIdAndStatusAndEventTypeOrderByOccurredAtDesc(
          tenantId, TrustAiEventStatus.DEAD_LETTERED, eventType, pageable);
    }
    return events.findByTenantIdAndStatusOrderByOccurredAtDesc(
        tenantId, TrustAiEventStatus.DEAD_LETTERED, pageable);
  }

  @Transactional(readOnly = true)
  public List<TrustAiProjectionCheckpoint> listCheckpoints(UUID tenantId, String projectorName,
      TrustAiProjectionStatus status, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    String projector = trimToNull(projectorName);
    if (projector != null && status != null) {
      return checkpoints.findByTenantIdAndProjectorNameAndStatusOrderByUpdatedAtDesc(
          tenantId, projector, status, pageable);
    }
    if (projector != null) {
      return checkpoints.findByTenantIdAndProjectorNameOrderByUpdatedAtDesc(tenantId, projector, pageable);
    }
    if (status != null) {
      return checkpoints.findByTenantIdAndStatusOrderByUpdatedAtDesc(tenantId, status, pageable);
    }
    return checkpoints.findByTenantIdOrderByUpdatedAtDesc(tenantId, pageable);
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
