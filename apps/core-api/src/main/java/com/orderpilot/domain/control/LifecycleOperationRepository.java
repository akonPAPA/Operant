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
   * serializes completion against concurrent re-lease or duplicate completion of the same operation so
   * owner, fencing-token, expiry, and terminal-state checks are atomic.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<LifecycleOperation> findWithLockByPublicId(String publicId);

  /**
   * Bounded, oldest-first selection of the next leasable operation under a pessimistic write lock with
   * SKIP LOCKED where supported. An in-flight lease becomes leasable exactly at its expiry instant,
   * matching completion semantics: completion requires {@code now < leaseExpiresAt}, while re-lease
   * permits {@code leaseExpiresAt <= now}. There is no dead instant at the boundary.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("""
      select o
      from LifecycleOperation o
      where o.state = :queued
         or (o.state in :inFlight and o.leaseExpiresAt is not null and o.leaseExpiresAt <= :now)
      order by o.createdAt asc
      """)
  List<LifecycleOperation> findLeasableWithLock(
      @Param("queued") LifecycleOperationState queued,
      @Param("inFlight") Collection<LifecycleOperationState> inFlight,
      @Param("now") Instant now,
      Pageable pageable);
}
