package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.intake.ProcessingJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-29 — the Core-API-owned worker runtime lifecycle: bounded job leasing (claim) and stale
 * in-flight recovery. The worker never owns tenant authority, job-status authority, or business truth;
 * it only drains already-admitted PENDING jobs and reports results through the separate intake path.
 *
 * <p>Claim is tenant-scoped (tenant resolved server-side from {@code TenantContext}, identical to the
 * result-intake boundary) so a worker can never lease another tenant's jobs. Claim only drains work that
 * was already admitted through the runtime-control gate at submission time — it never creates work and
 * never re-runs runtime-control, so it cannot double-charge quota/rate budget.
 *
 * <p>Stale recovery is a system-maintenance reaper (no HTTP surface, no scheduler introduced here): it
 * flips PROCESSING jobs whose lease has timed out back to the terminal FAILED state with a safe reason,
 * making them eligible for the existing OP-CAP-28 retry path. It never creates duplicate jobs.
 */
@Service
public class WorkerJobLeaseService {
  // Bounded claim batch sizing. Default keeps a poll cheap; the max caps any caller-supplied limit so a
  // worker can never request an unbounded drain.
  static final int DEFAULT_CLAIM_LIMIT = 10;
  static final int MAX_CLAIM_LIMIT = 50;
  // Bounded reaper batch sizing.
  static final int DEFAULT_RECOVERY_LIMIT = 50;
  static final int MAX_RECOVERY_LIMIT = 200;
  // Default lease timeout used when a caller does not specify a cutoff.
  static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);

  private final ProcessingJobRepository repository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public WorkerJobLeaseService(ProcessingJobRepository repository, AuditEventService auditEventService, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  /**
   * Lease a bounded batch of PENDING jobs for the current tenant, transitioning each PENDING -&gt;
   * PROCESSING within this transaction. Returns the leased jobs (oldest-queued first). No extraction or
   * provider call happens here — the heavy work is the worker's job, reported back via result intake.
   */
  @Transactional
  public List<ProcessingJob> claim(Integer limit) {
    UUID tenantId = TenantContext.requireTenantId();
    int clamped = clamp(limit, DEFAULT_CLAIM_LIMIT, MAX_CLAIM_LIMIT);
    List<ProcessingJob> claimable = repository.findByTenantIdAndStatusOrderByQueuedAtAsc(
        tenantId, ProcessingJobStatus.PENDING.name(), PageRequest.of(0, clamped));
    Instant now = clock.instant();
    for (ProcessingJob job : claimable) {
      // Defensive: only PENDING jobs are ever returned by the query, but re-check before mutating.
      if (!job.isClaimable()) {
        continue;
      }
      job.markProcessing(now);
      auditEventService.record("processing_job.claimed", "processing_job", job.getId().toString(), null,
          "{\"jobType\":\"" + job.getJobType() + "\",\"advisoryOnly\":true}");
    }
    return claimable;
  }

  /**
   * System-maintenance reaper. Flips PROCESSING jobs whose lease started before {@code cutoff} to FAILED
   * with a safe, non-sensitive reason so the existing retry path can govern any re-queue. Cross-tenant by
   * design (a fleet-wide reaper), with NO HTTP surface — callable only from trusted system code/tests.
   * Returns the number of jobs recovered. Bounded by {@code limit}; never creates a second job.
   */
  @Transactional
  public int recoverStaleProcessing(Instant cutoff, Integer limit) {
    int clamped = clamp(limit, DEFAULT_RECOVERY_LIMIT, MAX_RECOVERY_LIMIT);
    List<ProcessingJob> stale = repository.findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
        ProcessingJobStatus.PROCESSING.name(), cutoff, PageRequest.of(0, clamped));
    Instant now = clock.instant();
    for (ProcessingJob job : stale) {
      // No audit row here: this reaper is a deliberately cross-tenant system sweep with no single
      // TenantContext, and AuditEventService stamps the audit tenant from TenantContext. The recovery is
      // instead evidenced deterministically on the job itself — status=FAILED, finishedAt, and the safe
      // lastError token "stale_processing_timeout" — which is sufficient operational evidence and avoids
      // forcing a tenant identity onto a fleet-wide maintenance operation.
      job.markFailed("stale_processing_timeout", now);
    }
    return stale.size();
  }

  /** Convenience overload using the default lease timeout relative to the service clock. */
  @Transactional
  public int recoverStaleProcessing(Integer limit) {
    return recoverStaleProcessing(clock.instant().minus(DEFAULT_STALE_AFTER), limit);
  }

  private static int clamp(Integer limit, int dflt, int max) {
    if (limit == null || limit <= 0) {
      return dflt;
    }
    return Math.min(limit, max);
  }
}
