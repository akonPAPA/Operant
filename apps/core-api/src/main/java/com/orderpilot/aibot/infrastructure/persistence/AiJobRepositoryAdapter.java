package com.orderpilot.aibot.infrastructure.persistence;

import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AiJobRepositoryAdapter implements AiJobRepositoryPort {
  private final SpringDataAiJobRepository repository;

  public AiJobRepositoryAdapter(SpringDataAiJobRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public AiJob save(AiJob job) {
    AiJobJpaEntity entity;
    if (job.id() == null) {
      entity =
          new AiJobJpaEntity(
              job.publicId(),
              job.tenantId(),
              job.purpose().name(),
              job.botDefinitionVersionId(),
              job.status().name(),
              job.idempotencyKey(),
              job.requestSchemaVersion(),
              job.createdAt());
    } else {
      entity =
          repository
              .findById(job.id())
              .orElseThrow(() -> new IllegalStateException("ai_job_missing"));
      if (!entity.getTenantId().equals(job.tenantId())) {
        throw new IllegalStateException("tenant_mismatch");
      }
      if (entity.getRowVersion() != job.rowVersion()) {
        throw new IllegalStateException("ai_job_row_version_conflict");
      }
    }
    entity.overwriteFromDomain(
        job.status().name(),
        job.providerKey(),
        job.modelKey(),
        job.requestSchemaVersion(),
        job.responseSchemaVersion(),
        job.inputHash(),
        job.outputHash(),
        job.requestJson(),
        job.requestFingerprint(),
        job.inputClassification(),
        job.resultJson(),
        job.failureClass(),
        job.attemptCount(),
        job.leaseOwner(),
        job.leaseUntil(),
        job.fencingToken(),
        job.nextAttemptAt(),
        job.startedAt(),
        job.completedAt());
    return toDomain(repository.save(entity));
  }

  @Override
  @Transactional
  public IdempotentSave saveNewIdempotent(AiJob job) {
    if (job.id() != null) {
      throw new IllegalStateException("saveNewIdempotent_requires_new_job");
    }
    int inserted =
        repository.insertIfAbsent(
            job.publicId(),
            job.tenantId(),
            job.purpose().name(),
            job.botDefinitionVersionId(),
            job.status().name(),
            job.requestSchemaVersion(),
            job.requestJson(),
            job.requestFingerprint(),
            job.inputHash(),
            job.inputClassification(),
            job.idempotencyKey(),
            job.createdAt());
    AiJob stored =
        repository
            .findByTenantIdAndPurposeAndIdempotencyKey(
                job.tenantId(), job.purpose().name(), job.idempotencyKey())
            .map(this::toDomain)
            .orElseThrow(() -> new IllegalStateException("ai_job_upsert_row_missing"));
    return new IdempotentSave(stored, inserted == 1);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiJob> findByPublicIdAndTenantId(String publicId, UUID tenantId) {
    return repository.findByPublicIdAndTenantId(publicId, tenantId).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiJob> findByTenantIdAndPurposeAndIdempotencyKey(
      UUID tenantId, AiJobPurpose purpose, String idempotencyKey) {
    return repository
        .findByTenantIdAndPurposeAndIdempotencyKey(tenantId, purpose.name(), idempotencyKey)
        .map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiJob> findByIdAndTenantId(UUID id, UUID tenantId) {
    return repository.findByIdAndTenantId(id, tenantId).map(this::toDomain);
  }

  @Override
  @Transactional
  public Optional<ClaimedAiJob> claimNext(String workerId, Instant now, Instant leaseUntil) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("worker_id_required");
    }
    List<AiJobJpaEntity> locked = repository.lockNextClaimable(now);
    if (locked.isEmpty()) {
      return Optional.empty();
    }
    AiJobJpaEntity entity = locked.getFirst();
    entity.applyClaim(workerId.trim(), leaseUntil, now);
    AiJobJpaEntity saved = repository.save(entity);
    AiJob domain = toDomain(saved);
    return Optional.of(new ClaimedAiJob(domain, saved.getLeaseOwner(), saved.getFencingToken()));
  }

  private AiJob toDomain(AiJobJpaEntity entity) {
    AiJob job =
        new AiJob(
            entity.getPublicId(),
            entity.getTenantId(),
            AiJobPurpose.valueOf(entity.getPurpose()),
            entity.getBotDefinitionVersionId(),
            entity.getIdempotencyKey(),
            entity.getRequestSchemaVersion(),
            entity.getCreatedAt());
    job.rehydrate(
        AiJobStatus.valueOf(entity.getStatus()),
        entity.getProviderKey(),
        entity.getModelKey(),
        entity.getRequestSchemaVersion(),
        entity.getResponseSchemaVersion(),
        entity.getInputHash(),
        entity.getOutputHash(),
        entity.getRequestJson(),
        entity.getRequestFingerprint(),
        entity.getInputClassification(),
        entity.getResultJson(),
        entity.getFailureClass(),
        entity.getAttemptCount(),
        entity.getLeaseOwner(),
        entity.getLeaseUntil(),
        entity.getFencingToken(),
        entity.getNextAttemptAt(),
        entity.getStartedAt(),
        entity.getCompletedAt(),
        entity.getId(),
        entity.getRowVersion());
    return job;
  }
}
