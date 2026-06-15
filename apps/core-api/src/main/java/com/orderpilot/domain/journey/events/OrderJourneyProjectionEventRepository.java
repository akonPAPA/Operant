package com.orderpilot.domain.journey.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * OP-CAP-23 — tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable};
 * there is no unbounded per-request scan and no cross-tenant lookup. Every finder is tenant-isolated.
 */
public interface OrderJourneyProjectionEventRepository
    extends JpaRepository<OrderJourneyProjectionEvent, UUID> {
  Optional<OrderJourneyProjectionEvent> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<OrderJourneyProjectionEvent> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

  List<OrderJourneyProjectionEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

  List<OrderJourneyProjectionEvent> findByTenantIdAndStatusOrderByOccurredAtDesc(
      UUID tenantId, JourneyProjectionEventStatus status, Pageable pageable);

  List<OrderJourneyProjectionEvent> findByTenantIdAndStatusOrderByOccurredAtAsc(
      UUID tenantId, JourneyProjectionEventStatus status, Pageable pageable);

  long countByTenantIdAndStatus(UUID tenantId, JourneyProjectionEventStatus status);

  @Query("select count(e) from OrderJourneyProjectionEvent e where e.tenantId = :tenantId "
      + "and e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount < :maxRetry")
  long countRetryableFailedEvents(@Param("tenantId") UUID tenantId, @Param("maxRetry") int maxRetry);

  @Query("select count(e) from OrderJourneyProjectionEvent e where e.tenantId = :tenantId and ("
      + "(e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount >= :maxRetry) "
      + "or e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.DEAD_LETTERED)")
  long countPermanentFailedEvents(@Param("tenantId") UUID tenantId, @Param("maxRetry") int maxRetry);

  @Query("select count(e) from OrderJourneyProjectionEvent e where e.tenantId = :tenantId "
      + "and e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PROCESSING "
      + "and (exists (select c.id from OrderJourneyProjectionCheckpoint c where c.tenantId = e.tenantId "
      + "and c.eventId = e.id and c.projectorName = :projectorName "
      + "and c.status = com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus.STARTED "
      + "and c.startedAt <= :staleBefore) "
      + "or (not exists (select c2.id from OrderJourneyProjectionCheckpoint c2 where c2.tenantId = e.tenantId "
      + "and c2.eventId = e.id and c2.projectorName = :projectorName) "
      + "and e.createdAt <= :staleBefore))")
  long countStaleProcessingEvents(
      @Param("tenantId") UUID tenantId, @Param("projectorName") String projectorName,
      @Param("staleBefore") Instant staleBefore);

  /**
   * Bounded, tenant-scoped pending batch: PENDING events plus FAILED events whose retry window has opened
   * and whose retry cap is not yet reached. Ordered oldest-first so processing is fair and progresses.
   */
  @Query("select e from OrderJourneyProjectionEvent e where e.tenantId = :tenantId and ("
      + "e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PENDING "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount < :maxRetry and (e.nextRetryAt is null or e.nextRetryAt <= :now))) "
      + "order by e.occurredAt asc")
  List<OrderJourneyProjectionEvent> findPendingBatch(
      @Param("tenantId") UUID tenantId, @Param("maxRetry") int maxRetry, @Param("now") Instant now,
      Pageable pageable);

  /**
   * OP-CAP-26 — bounded tenant recovery batch. Includes ordinary drainable events plus stale PROCESSING rows
   * that may have been left behind by a stopped worker. Terminal rows are excluded and ordering remains
   * deterministic oldest-first.
   */
  @Query("select e from OrderJourneyProjectionEvent e where e.tenantId = :tenantId and ("
      + "e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PENDING "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount < :maxRetry and (e.nextRetryAt is null or e.nextRetryAt <= :now)) "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PROCESSING "
      + "and (exists (select c.id from OrderJourneyProjectionCheckpoint c where c.tenantId = e.tenantId "
      + "and c.eventId = e.id and c.projectorName = :projectorName "
      + "and c.status = com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus.STARTED "
      + "and c.startedAt <= :staleBefore) "
      + "or (not exists (select c2.id from OrderJourneyProjectionCheckpoint c2 where c2.tenantId = e.tenantId "
      + "and c2.eventId = e.id and c2.projectorName = :projectorName) "
      + "and e.createdAt <= :staleBefore)))) "
      + "order by e.occurredAt asc, e.id asc")
  List<OrderJourneyProjectionEvent> findRecoverableBatch(
      @Param("tenantId") UUID tenantId, @Param("maxRetry") int maxRetry, @Param("now") Instant now,
      @Param("projectorName") String projectorName, @Param("staleBefore") Instant staleBefore,
      Pageable pageable);

  /**
   * OP-CAP-25 — bounded, fair, cross-tenant discovery for the controlled drain runtime. Returns only the
   * distinct tenant ids that currently have drainable work (PENDING, or FAILED whose retry window has opened
   * and whose retry cap is not yet reached) — never DEAD_LETTERED. Ordered by each tenant's oldest drainable
   * event so no tenant is starved, and always clamped by the supplied {@link Pageable} so the scan is bounded
   * and never loads the events themselves (only tenant ids). The drain then processes each tenant with the
   * existing tenant-scoped {@code findPendingBatch}/runner, so per-tenant isolation is preserved.
   */
  @Query("select e.tenantId from OrderJourneyProjectionEvent e where "
      + "e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PENDING "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount < :maxRetry and (e.nextRetryAt is null or e.nextRetryAt <= :now)) "
      + "group by e.tenantId order by min(e.occurredAt) asc")
  List<UUID> findTenantIdsWithPendingEvents(
      @Param("maxRetry") int maxRetry, @Param("now") Instant now, Pageable pageable);

  @Query("select e.tenantId from OrderJourneyProjectionEvent e where "
      + "e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PENDING "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.FAILED "
      + "and e.retryCount < :maxRetry and (e.nextRetryAt is null or e.nextRetryAt <= :now)) "
      + "or (e.status = com.orderpilot.domain.journey.events.JourneyProjectionEventStatus.PROCESSING "
      + "and (exists (select c.id from OrderJourneyProjectionCheckpoint c where c.tenantId = e.tenantId "
      + "and c.eventId = e.id and c.projectorName = :projectorName "
      + "and c.status = com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus.STARTED "
      + "and c.startedAt <= :staleBefore) "
      + "or (not exists (select c2.id from OrderJourneyProjectionCheckpoint c2 where c2.tenantId = e.tenantId "
      + "and c2.eventId = e.id and c2.projectorName = :projectorName) "
      + "and e.createdAt <= :staleBefore))) "
      + "group by e.tenantId order by min(e.occurredAt) asc")
  List<UUID> findTenantIdsWithRecoverableEvents(
      @Param("maxRetry") int maxRetry, @Param("now") Instant now,
      @Param("projectorName") String projectorName, @Param("staleBefore") Instant staleBefore,
      Pageable pageable);
}
