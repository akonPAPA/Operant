package com.orderpilot.domain.intake;
import jakarta.persistence.LockModeType; import jakarta.persistence.QueryHint; import java.time.Instant; import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock; import org.springframework.data.jpa.repository.QueryHints;
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId);
  // OP-CAP-28: bounded, tenant-scoped, most-recent-first page for the safe status/list contract (no full scan).
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId, Pageable pageable);
  Optional<ProcessingJob> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ProcessingJob> findFirstByTenantIdAndTargetTypeAndTargetIdAndStatus(UUID tenantId, String targetType, UUID targetId, String status);
  // OP-CAP-21: bounded counts + most-recent-job lookup for the Command Center runtime health summary.
  long countByTenantIdAndStatus(UUID tenantId, String status);
  Optional<ProcessingJob> findFirstByTenantIdOrderByQueuedAtDesc(UUID tenantId);
  // OP-CAP-29: bounded, tenant-scoped oldest-first lease selection (worker claims PENDING jobs).
  // Pessimistic write lock + SKIP LOCKED makes the claim atomic at the row level so two concurrent
  // workers can never lease the same job: on PostgreSQL Hibernate emits FOR UPDATE SKIP LOCKED, so a
  // competing worker skips rows already locked by an in-flight claim (disjoint batches, no blocking, no
  // double-own). On H2 (Dialect.supportsSkipLocked() == false) Hibernate degrades to plain FOR UPDATE,
  // which is still correct (the competitor blocks then re-evaluates status) — only the throughput, not
  // the exclusivity, differs. The SKIP_LOCKED hint value (-2) is ignored where the dialect lacks support.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  List<ProcessingJob> findWithLockByTenantIdAndStatusOrderByQueuedAtAsc(UUID tenantId, String status, Pageable pageable);
  // OP-CAP-29: bounded stale-PROCESSING reaper selection (system maintenance; oldest in-flight first).
  List<ProcessingJob> findByStatusAndStartedAtBeforeOrderByStartedAtAsc(String status, Instant cutoff, Pageable pageable);
}