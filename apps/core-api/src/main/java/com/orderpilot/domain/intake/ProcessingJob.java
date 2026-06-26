package com.orderpilot.domain.intake;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="processing_job")
public class ProcessingJob {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="job_type",nullable=false) private String jobType; @Column(name="target_type",nullable=false) private String targetType; @Column(name="target_id",nullable=false) private UUID targetId; @Column(nullable=false) private String status; @Column(nullable=false) private int priority; @Column(nullable=false) private int attempts; @Column(name="max_attempts",nullable=false) private int maxAttempts; @Column(name="last_error") private String lastError; @Column(name="queued_at",nullable=false) private Instant queuedAt; @Column(name="started_at") private Instant startedAt; @Column(name="finished_at") private Instant finishedAt; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected ProcessingJob() {}
  public ProcessingJob(UUID tenantId, String jobType, String targetType, UUID targetId, int priority, Instant now){this.tenantId=tenantId; this.jobType=jobType; this.targetType=targetType; this.targetId=targetId; this.status="PENDING"; this.priority=priority; this.attempts=0; this.maxAttempts=3; this.queuedAt=now; this.createdAt=now; this.updatedAt=now;}
  // OP-CAP-28: a controlled requeue. Only a FAILED job under its attempt ceiling is retryable (see
  // isRetryable); the service enforces eligibility fail-closed before calling this. Each retry bumps
  // attempts so manual requeues are bounded by maxAttempts and can never loop unbounded.
  public void retry(Instant now){this.status=ProcessingJobStatus.PENDING.name(); this.lastError=null; this.attempts++; this.queuedAt=now; this.finishedAt=null; this.updatedAt=now;}
  // OP-CAP-29: a worker lease/claim. Only a PENDING job is claimable (see isClaimable); the lease
  // service enforces eligibility before calling this. Moves PENDING -> PROCESSING and stamps startedAt.
  // It does NOT touch attempts — attempts counts controlled requeues (retry), not in-flight leases, so
  // a claim+stale-recovery+retry cycle stays bounded by maxAttempts via the retry path alone.
  public void markProcessing(Instant now){this.status=ProcessingJobStatus.PROCESSING.name(); this.startedAt=now; this.finishedAt=null; this.updatedAt=now;}
  // OP-CAP-29: only PENDING jobs may be leased; PROCESSING/terminal jobs are never re-claimed.
  public boolean isClaimable(){return ProcessingJobStatus.PENDING.name().equals(status);}
  // OP-CAP-29: a fresh advisory result is accepted only while the job is still active (PENDING or
  // PROCESSING). Once terminal, a late/runless result must not resurrect or overwrite the job.
  public boolean isActiveForResult(){return ProcessingJobStatus.PENDING.name().equals(status) || ProcessingJobStatus.PROCESSING.name().equals(status);}
  // OP-CAP-28: retry is allowed only from the terminal FAILED state and only while attempts remain.
  // SUCCEEDED / PROCESSING / PENDING / NEEDS_REVIEW / REJECTED are never re-queued from the control layer.
  public boolean isRetryable(){return ProcessingJobStatus.FAILED.name().equals(status) && attempts < maxAttempts;}
  // OP-CAP-07D: narrow terminal transitions applied when an advisory AI-worker result is received.
  // These only move the processing job's own status; they never touch any business entity. lastError
  // carries a bounded, safe failure token only (no stack traces, no raw customer/provider content).
  public void markSucceeded(Instant now){this.status=ProcessingJobStatus.SUCCEEDED.name(); this.lastError=null; this.finishedAt=now; this.updatedAt=now;}
  public void markNeedsReview(Instant now){this.status=ProcessingJobStatus.NEEDS_REVIEW.name(); this.lastError=null; this.finishedAt=now; this.updatedAt=now;}
  public void markFailed(String safeReason, Instant now){this.status=ProcessingJobStatus.FAILED.name(); this.lastError=boundedReason(safeReason); this.finishedAt=now; this.updatedAt=now;}
  public void markRejected(String safeReason, Instant now){this.status=ProcessingJobStatus.REJECTED.name(); this.lastError=boundedReason(safeReason); this.finishedAt=now; this.updatedAt=now;}
  // OP-CAP-54: a controlled, approval-gated operational status repair. The deterministic
  // ProcessingJobStatusRepairValidator is the ONLY driver, and the only allowlisted repair target is
  // FAILED — unsticking a wedged PENDING/PROCESSING job to a terminal FAILED state from which the existing
  // control-plane retry path (isRetryable) can requeue it. It mutates only THIS job's own status/lastError/
  // finishedAt; it touches no business entity, runs no SQL/script, and triggers no external side effect.
  public void applyOperationalStatusRepair(ProcessingJobStatus target, String safeReason, Instant now){
    if (target != ProcessingJobStatus.FAILED) {
      throw new IllegalArgumentException("Unsupported operational repair target status");
    }
    this.status=target.name(); this.lastError=boundedReason(safeReason); this.finishedAt=now; this.updatedAt=now;
  }
  private static String boundedReason(String reason){ if (reason == null) return null; return reason.length() > 500 ? reason.substring(0, 500) : reason; }
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getJobType(){return jobType;} public String getTargetType(){return targetType;} public UUID getTargetId(){return targetId;} public String getStatus(){return status;} public Instant getQueuedAt(){return queuedAt;}
  // OP-CAP-28: bounded, non-sensitive operational fields for the safe status contract. lastError is
  // intentionally NOT exposed via a getter consumed by responses — failure detail is mapped to a safe
  // business message in the DTO layer instead.
  public int getAttempts(){return attempts;} public int getMaxAttempts(){return maxAttempts;} public Instant getUpdatedAt(){return updatedAt;} public Instant getCreatedAt(){return createdAt;} public Instant getFinishedAt(){return finishedAt;} public Instant getStartedAt(){return startedAt;}
}
