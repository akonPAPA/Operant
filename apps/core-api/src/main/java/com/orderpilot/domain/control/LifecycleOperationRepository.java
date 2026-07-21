package com.orderpilot.domain.control;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface LifecycleOperationRepository extends JpaRepository<LifecycleOperation, UUID> {

  /** Idempotency lookup by the deduplication key (operation type + hashed idempotency key). */
  Optional<LifecycleOperation> findByOperationTypeAndIdempotencyKeyHash(
      LifecycleOperationType operationType, String idempotencyKeyHash);

  /** Read a single operation by its opaque public id (staff read + executor completion). */
  Optional<LifecycleOperation> findByPublicId(String publicId);

  /**
   * Row-locked fetch by public id used for the terminal-completion transition. A pessimistic write lock
   * (FOR UPDATE on PostgreSQL and H2) serializes a completion against a concurrent (re-)lease or a
   * duplicate completion of the SAME operation so the fencing-token check and terminal transition are
   * atomic. A wrong/unknown public id matches no row and locks nothing.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<LifecycleOperation> findWithLockByPublicId(String publicId);

  /**
   * Bounded, oldest-first selection of the next leasable operation(s) under a pessimistic write lock with
   * SKIP LOCKED. On PostgreSQL Hibernate emits FOR UPDATE SKIP LOCKED, so two concurrent executors can
   * never lease the same operation (a competitor skips a row already locked by an in-flight lease). On H2
   * (no SKIP LOCKED support) it degrades to plain FOR UPDATE, which is still correct (the competitor
   * blocks, then re-evaluates state) — only throughput, not exclusivity, differs. The SKIP_LOCKED hint
   * value (-2) is ignored where the dialect lacks support.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("""
      select o
      from LifecycleOperation o
      where o.state = :queued
         or (o.state in :inFlight and o.leaseExpiresAt is not null and o.leaseExpiresAt < :now)
      order by o.createdAt asc
      """)
  List<LifecycleOperation> findLeasableWithLock(
      @Param("queued") LifecycleOperationState queued,
      @Param("inFlight") Collection<LifecycleOperationState> inFlight,
      @Param("now") Instant now,
      Pageable pageable);
}
