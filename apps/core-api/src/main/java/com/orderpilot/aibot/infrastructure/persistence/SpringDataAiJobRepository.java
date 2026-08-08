package com.orderpilot.aibot.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataAiJobRepository extends JpaRepository<AiJobJpaEntity, UUID> {
  Optional<AiJobJpaEntity> findByPublicIdAndTenantId(String publicId, UUID tenantId);

  Optional<AiJobJpaEntity> findByTenantIdAndPurposeAndIdempotencyKey(
      UUID tenantId, String purpose, String idempotencyKey);

  Optional<AiJobJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

  @Query(
      value =
          """
          SELECT * FROM aibot_ai_job j
          WHERE (
              j.status = 'REQUESTED'
              OR (j.next_attempt_at IS NOT NULL AND j.next_attempt_at <= :now)
              -- Recover jobs whose worker died mid-flight: any non-terminal row still holding an
              -- expired lease (LEASED/RUNNING/OUTPUT_RECEIVED/...) becomes claimable again. Without
              -- this an orphaned lease with a null next_attempt_at is never re-selected and the job
              -- is stuck forever. The `lease_until <= :now` clause below still protects active leases.
              OR (j.lease_until IS NOT NULL AND j.lease_until <= :now)
            )
            AND (j.lease_until IS NULL OR j.lease_until <= :now)
            AND j.status NOT IN ('SUGGESTION_READY','INVALID','FAILED','REJECTED','CANCELLED','STALE')
          ORDER BY j.next_attempt_at NULLS FIRST, j.created_at ASC, j.id ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 1
          """,
      nativeQuery = true)
  List<AiJobJpaEntity> lockNextClaimable(@Param("now") Instant now);

  /**
   * Atomic idempotent insert of a new REQUESTED job. {@code ON CONFLICT DO NOTHING} makes a
   * concurrent duplicate a no-op (returns 0 rows) instead of raising a unique-constraint violation,
   * so the caller can read back the winning row without poisoning the transaction. Columns not
   * listed keep their schema defaults (provider_key/model_key='none', result_json='{}',
   * attempt_count/fencing_token/row_version=0).
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO aibot_ai_job
            (public_id, tenant_id, purpose, bot_definition_version_id, status,
             request_schema_version, request_json, request_fingerprint, input_hash,
             input_classification, idempotency_key, created_at)
          VALUES
            (:publicId, :tenantId, :purpose, :versionId, :status,
             :requestSchemaVersion, CAST(:requestJson AS jsonb), :requestFingerprint, :inputHash,
             :inputClassification, :idempotencyKey, :createdAt)
          ON CONFLICT (tenant_id, purpose, idempotency_key) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("publicId") String publicId,
      @Param("tenantId") UUID tenantId,
      @Param("purpose") String purpose,
      @Param("versionId") UUID versionId,
      @Param("status") String status,
      @Param("requestSchemaVersion") String requestSchemaVersion,
      @Param("requestJson") String requestJson,
      @Param("requestFingerprint") String requestFingerprint,
      @Param("inputHash") String inputHash,
      @Param("inputClassification") String inputClassification,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("createdAt") Instant createdAt);
}
