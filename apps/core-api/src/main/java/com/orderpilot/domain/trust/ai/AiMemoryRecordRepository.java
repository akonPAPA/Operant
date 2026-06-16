package com.orderpilot.domain.trust.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable}; there is no
 * unbounded per-request scan and no cross-tenant/global lookup. Every finder is tenant-isolated.
 */
public interface AiMemoryRecordRepository extends JpaRepository<AiMemoryRecord, UUID> {
  Optional<AiMemoryRecord> findByIdAndTenantId(UUID id, UUID tenantId);

  /** Current active version for a key (at most one ACTIVE version per key at a time). */
  Optional<AiMemoryRecord> findFirstByTenantIdAndNamespaceAndMemoryKeyAndStatusOrderByVersionDesc(
      UUID tenantId, AiMemoryNamespace namespace, String memoryKey, AiMemoryStatus status);

  /** Latest version (any status) for a key — used to compute the next supersede version. */
  Optional<AiMemoryRecord> findFirstByTenantIdAndNamespaceAndMemoryKeyOrderByVersionDesc(
      UUID tenantId, AiMemoryNamespace namespace, String memoryKey);

  /** Bounded sweep of ACTIVE records past their TTL for one tenant. */
  List<AiMemoryRecord> findByTenantIdAndStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
      UUID tenantId, AiMemoryStatus status, Instant now, Pageable pageable);

  /**
   * Bounded advisory search. Returns only same-tenant ACTIVE records by default; expired (past-TTL)
   * records and low-confidence records are excluded unless explicitly requested. Ranked by weight then
   * recency. Never crosses a tenant boundary.
   */
  @Query("select r from AiMemoryRecord r "
      + "where r.tenantId = :tenantId and r.namespace = :namespace and r.status = :status "
      + "and (:includeExpired = true or r.expiresAt is null or r.expiresAt > :now) "
      + "and (:includeLowConfidence = true or r.confidence >= :minConfidence) "
      + "and (:memoryKey is null or r.memoryKey = :memoryKey) "
      + "order by r.weight desc, r.updatedAt desc")
  List<AiMemoryRecord> search(
      @Param("tenantId") UUID tenantId,
      @Param("namespace") AiMemoryNamespace namespace,
      @Param("status") AiMemoryStatus status,
      @Param("includeExpired") boolean includeExpired,
      @Param("includeLowConfidence") boolean includeLowConfidence,
      @Param("minConfidence") BigDecimal minConfidence,
      @Param("memoryKey") String memoryKey,
      @Param("now") Instant now,
      Pageable pageable);
}
