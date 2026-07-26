package com.orderpilot.domain.intake;
import jakarta.persistence.LockModeType; import jakarta.persistence.QueryHint; import java.time.Instant; import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Lock; import org.springframework.data.jpa.repository.Query; import org.springframework.data.jpa.repository.QueryHints; import org.springframework.data.repository.query.Param;
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId);
  // OP-CAP-28: bounded, tenant-scoped, most-recent-first page for the safe status/list contract (no full scan).
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId, Pageable pageable);
  Optional<ProcessingJob> findByIdAndTenantId(UUID id, UUID tenantId);
  // OP-CAP-30: tenant-scoped, row-locked single-job lookup for result drain. A pessimistic write lock
  // (FOR UPDATE on PostgreSQL and H2) serializes concurrent/duplicate result drains for the SAME job on
  // the database row itself — the first drain creates the advisory run + terminal transition and commits,
  // a racing duplicate blocks here, and once it acquires the lock it observes the committed run and is
  // handled as an idempotent duplicate. No SKIP LOCKED here: the drain must WAIT for the in-flight winner
  // (and then see its committed state), not skip the row like the claim path does. Wrong-tenant lookups
  // match no row and lock nothing, preserving the existing cross-tenant not-found behavior.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ProcessingJob> findWithLockByIdAndTenantId(UUID id, UUID tenantId);
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
  // OP-CAP-30.1 / Wave 01J: explicitly system-wide, bounded stale-PROCESSING maintenance selection.
  // This is deliberately NOT tenant-scoped: one trusted fleet reaper recovers timed-out leases across
  // tenants. It is not used by a tenant/support read API and returns rows only to WorkerJobLeaseService,
  // which applies the narrow PROCESSING -> FAILED maintenance transition and returns only a count.
  // The reaper is also a terminal-state writer, so it waits on the same row-level lock protocol as result
  // intake instead of racing an unlocked stale read against the result drain's terminal transition.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select j
      from ProcessingJob j
      where j.status = :status
      and j.startedAt is not null
      and j.startedAt < :cutoff
      order by j.startedAt asc
      """)
  List<ProcessingJob> findSystemMaintenanceStaleProcessingWithLock(
      @Param("status") String status,
      @Param("cutoff") Instant cutoff,
      Pageable pageable);
}
