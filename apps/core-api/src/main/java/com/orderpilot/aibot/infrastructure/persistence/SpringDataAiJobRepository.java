package com.orderpilot.aibot.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
            )
            AND (j.lease_until IS NULL OR j.lease_until <= :now)
            AND j.status NOT IN ('SUGGESTION_READY','INVALID','FAILED','REJECTED','CANCELLED','STALE')
          ORDER BY j.next_attempt_at NULLS FIRST, j.created_at ASC, j.id ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 1
          """,
      nativeQuery = true)
  List<AiJobJpaEntity> lockNextClaimable(@Param("now") Instant now);
}
