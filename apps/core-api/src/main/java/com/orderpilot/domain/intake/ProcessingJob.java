package com.orderpilot.domain.intake;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="processing_job")
public class ProcessingJob {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="job_type",nullable=false) private String jobType; @Column(name="target_type",nullable=false) private String targetType; @Column(name="target_id",nullable=false) private UUID targetId; @Column(nullable=false) private String status; @Column(nullable=false) private int priority; @Column(nullable=false) private int attempts; @Column(name="max_attempts",nullable=false) private int maxAttempts; @Column(name="last_error") private String lastError; @Column(name="queued_at",nullable=false) private Instant queuedAt; @Column(name="started_at") private Instant startedAt; @Column(name="finished_at") private Instant finishedAt; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected ProcessingJob() {}
  public ProcessingJob(UUID tenantId, String jobType, String targetType, UUID targetId, int priority, Instant now){this.tenantId=tenantId; this.jobType=jobType; this.targetType=targetType; this.targetId=targetId; this.status="PENDING"; this.priority=priority; this.attempts=0; this.maxAttempts=3; this.queuedAt=now; this.createdAt=now; this.updatedAt=now;}
  public void retry(Instant now){this.status="PENDING"; this.lastError=null; this.queuedAt=now; this.updatedAt=now;}
  // OP-CAP-07D: narrow terminal transitions applied when an advisory AI-worker result is received.
  // These only move the processing job's own status; they never touch any business entity. lastError
  // carries a bounded, safe failure token only (no stack traces, no raw customer/provider content).
  public void markSucceeded(Instant now){this.status=ProcessingJobStatus.SUCCEEDED.name(); this.lastError=null; this.finishedAt=now; this.updatedAt=now;}
  public void markNeedsReview(Instant now){this.status=ProcessingJobStatus.NEEDS_REVIEW.name(); this.lastError=null; this.finishedAt=now; this.updatedAt=now;}
  public void markFailed(String safeReason, Instant now){this.status=ProcessingJobStatus.FAILED.name(); this.lastError=boundedReason(safeReason); this.finishedAt=now; this.updatedAt=now;}
  public void markRejected(String safeReason, Instant now){this.status=ProcessingJobStatus.REJECTED.name(); this.lastError=boundedReason(safeReason); this.finishedAt=now; this.updatedAt=now;}
  private static String boundedReason(String reason){ if (reason == null) return null; return reason.length() > 500 ? reason.substring(0, 500) : reason; }
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getJobType(){return jobType;} public String getTargetType(){return targetType;} public UUID getTargetId(){return targetId;} public String getStatus(){return status;} public Instant getQueuedAt(){return queuedAt;}
}
