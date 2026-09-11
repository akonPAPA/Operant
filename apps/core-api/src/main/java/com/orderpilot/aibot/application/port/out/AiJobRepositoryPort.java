package com.orderpilot.aibot.application.port.out;

import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiJobRepositoryPort {
  AiJob save(AiJob job);

  Optional<AiJob> findByPublicIdAndTenantId(String publicId, UUID tenantId);

  Optional<AiJob> findByTenantIdAndPurposeAndIdempotencyKey(
      UUID tenantId, AiJobPurpose purpose, String idempotencyKey);

  Optional<AiJob> findByIdAndTenantId(UUID id, UUID tenantId);

  /**
   * Atomically claims the next executable job under FOR UPDATE SKIP LOCKED.
   * Returns empty when no claimable row exists.
   */
  Optional<ClaimedAiJob> claimNext(String workerId, Instant now, Instant leaseUntil);

  record ClaimedAiJob(AiJob job, String leaseOwner, long fencingToken) {}
}
