package com.orderpilot.aibot.application.service;

import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.ClaimedAiJob;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiJobDrainService {
  private static final Logger log = LoggerFactory.getLogger(AiJobDrainService.class);

  private final AiJobRepositoryPort aiJobRepository;
  private final AiJobProcessingService processingService;
  private final OperantAiProperties aiProperties;
  private final Clock clock;
  private final String workerId = "aibot-drain-" + UUID.randomUUID();

  public AiJobDrainService(
      AiJobRepositoryPort aiJobRepository,
      AiJobProcessingService processingService,
      OperantAiProperties aiProperties,
      Clock clock) {
    this.aiJobRepository = aiJobRepository;
    this.processingService = processingService;
    this.aiProperties = aiProperties;
    this.clock = clock;
  }

  public AiJobDrainSummary drainOnce() {
    int claimed = 0;
    int completed = 0;
    int invalid = 0;
    int failed = 0;
    int deferred = 0;
    int leaseConflicts = 0;
    int batchSize = aiProperties.getWorkerBatchSize();
    Duration lease = aiProperties.getWorkerLeaseDuration();

    for (int i = 0; i < batchSize; i++) {
      // Fresh per-claim timestamp: draining a batch can take real wall-clock time (each job may do
      // provider I/O), so a single batch-start `now` would anchor every lease deadline to the start
      // and hand later claims a lease window that is already partly — or fully — elapsed.
      Instant now = clock.instant();
      Optional<ClaimedAiJob> claim;
      try {
        claim = aiJobRepository.claimNext(workerId, now, now.plus(lease));
      } catch (RuntimeException ex) {
        leaseConflicts++;
        log.warn("aibot_drain_claim_failed failureClass={}", ex.getClass().getSimpleName());
        continue;
      }
      if (claim.isEmpty()) {
        break;
      }
      claimed++;
      ClaimedAiJob claimedJob = claim.get();
      try {
        processingService.processClaimedJob(claimedJob);
        AiJob after =
            aiJobRepository
                .findByPublicIdAndTenantId(claimedJob.job().publicId(), claimedJob.job().tenantId())
                .orElse(null);
        if (after == null) {
          failed++;
        } else if (after.status() == AiJobStatus.SUGGESTION_READY) {
          completed++;
        } else if (after.status() == AiJobStatus.INVALID) {
          invalid++;
        } else if (after.status() == AiJobStatus.REQUESTED) {
          deferred++;
        } else if (after.status().isTerminal()) {
          failed++;
        } else {
          deferred++;
        }
      } catch (IllegalStateException ex) {
        if (ex.getMessage() != null
            && (ex.getMessage().contains("lease") || ex.getMessage().contains("fencing"))) {
          leaseConflicts++;
        } else {
          failed++;
        }
        log.warn("aibot_drain_job_failed failureClass={}", ex.getClass().getSimpleName());
      } catch (RuntimeException ex) {
        failed++;
        log.warn("aibot_drain_job_failed failureClass={}", ex.getClass().getSimpleName());
      }
    }
    if (claimed > 0) {
      log.info(
          "aibot_drain claimed={} completed={} invalid={} failed={} deferred={} leaseConflicts={}",
          claimed,
          completed,
          invalid,
          failed,
          deferred,
          leaseConflicts);
    }
    return new AiJobDrainSummary(claimed, completed, invalid, failed, deferred, leaseConflicts);
  }
}
