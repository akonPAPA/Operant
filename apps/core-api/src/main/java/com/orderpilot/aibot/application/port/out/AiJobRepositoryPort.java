package com.orderpilot.aibot.application.port.out;

import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiJobRepositoryPort {
  AiJob save(AiJob job);

  /**
   * Concurrency-safe idempotent insert of a brand-new job keyed by (tenant, purpose,
   * idempotencyKey). Uses a single atomic {@code INSERT ... ON CONFLICT DO NOTHING} so a lost race
   * against a concurrent writer is NOT an error: the persisted row (ours or the winner's) is always
   * returned, with {@code inserted} true only when this call actually created it. Callers use
   * {@code inserted} to run create-only side effects (audit) exactly once and compare the returned
   * fingerprint to detect a same-key/different-request conflict.
   */
  IdempotentSave saveNewIdempotent(AiJob job);

  record IdempotentSave(AiJob job, boolean inserted) {}

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
