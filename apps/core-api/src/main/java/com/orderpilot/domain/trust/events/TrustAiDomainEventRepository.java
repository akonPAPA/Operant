package com.orderpilot.domain.trust.events;

import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable}; there is no
 * unbounded per-request scan and no cross-tenant lookup. Every finder is tenant-isolated.
 */
public interface TrustAiDomainEventRepository extends JpaRepository<TrustAiDomainEvent, UUID> {
  Optional<TrustAiDomainEvent> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<TrustAiDomainEvent> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

  List<TrustAiDomainEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

  List<TrustAiDomainEvent> findByTenantIdAndStatusOrderByOccurredAtDesc(
      UUID tenantId, TrustAiEventStatus status, Pageable pageable);

  List<TrustAiDomainEvent> findByTenantIdAndEventTypeOrderByOccurredAtDesc(
      UUID tenantId, TrustAiEventType eventType, Pageable pageable);

  List<TrustAiDomainEvent> findByTenantIdAndStatusAndEventTypeOrderByOccurredAtDesc(
      UUID tenantId, TrustAiEventStatus status, TrustAiEventType eventType, Pageable pageable);

  List<TrustAiDomainEvent> findByTenantIdAndSourceTypeAndSourceIdOrderByOccurredAtDesc(
      UUID tenantId, AiMemorySourceType sourceType, UUID sourceId, Pageable pageable);

  List<TrustAiDomainEvent> findByTenantIdAndSourceTypeOrderByOccurredAtDesc(
      UUID tenantId, AiMemorySourceType sourceType, Pageable pageable);

  /**
   * Bounded, tenant-scoped pending batch: PENDING events plus FAILED events whose retry window has opened
   * and whose retry cap is not yet reached. Ordered oldest-first so processing is fair and progresses.
   */
  @Query("select e from TrustAiDomainEvent e where e.tenantId = :tenantId and ("
      + "e.status = com.orderpilot.domain.trust.events.TrustAiEventStatus.PENDING "
      + "or (e.status = com.orderpilot.domain.trust.events.TrustAiEventStatus.FAILED "
      + "and e.retryCount < :maxRetry and (e.nextRetryAt is null or e.nextRetryAt <= :now))) "
      + "order by e.occurredAt asc")
  List<TrustAiDomainEvent> findPendingBatch(
      @Param("tenantId") UUID tenantId, @Param("maxRetry") int maxRetry, @Param("now") Instant now,
      Pageable pageable);
}
