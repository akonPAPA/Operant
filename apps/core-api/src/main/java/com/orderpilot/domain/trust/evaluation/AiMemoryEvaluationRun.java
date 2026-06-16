package com.orderpilot.domain.trust.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * A tenant-scoped governance evaluation run over advisory memory retrieval. It records only deterministic
 * outcome counts/scores — never raw prompts/documents/messages. Evaluation is read-only with respect to
 * memory and business state; it exists to measure whether governed memory helps without becoming
 * authoritative.
 */
@Entity
@Table(name = "ai_memory_evaluation_run")
public class AiMemoryEvaluationRun {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "run_type", nullable = false, length = 48) private AiMemoryEvaluationRunType runType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private AiMemoryEvaluationStatus status;

  @Column(name = "started_at") private Instant startedAt;

  @Column(name = "completed_at") private Instant completedAt;

  @Column(name = "total_cases", nullable = false) private int totalCases;

  @Column(name = "passed_cases", nullable = false) private int passedCases;

  @Column(name = "failed_cases", nullable = false) private int failedCases;

  @Column(name = "average_score", precision = 6, scale = 2) private BigDecimal averageScore;

  @Column(name = "created_by") private UUID createdBy;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiMemoryEvaluationRun() {}

  public AiMemoryEvaluationRun(UUID tenantId, AiMemoryEvaluationRunType runType, UUID createdBy, Instant now) {
    this.tenantId = tenantId;
    this.runType = runType;
    this.status = AiMemoryEvaluationStatus.PENDING;
    this.totalCases = 0;
    this.passedCases = 0;
    this.failedCases = 0;
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  public void markRunning(Instant now) {
    this.status = AiMemoryEvaluationStatus.RUNNING;
    this.startedAt = now;
  }

  /** Records the deterministic outcome of executing the run's cases. */
  public void complete(int total, int passed, int failed, BigDecimal averageScore, Instant now) {
    this.totalCases = total;
    this.passedCases = passed;
    this.failedCases = failed;
    this.averageScore = averageScore;
    this.status = failed == 0 ? AiMemoryEvaluationStatus.PASSED : AiMemoryEvaluationStatus.FAILED;
    this.completedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public AiMemoryEvaluationRunType getRunType() { return runType; }
  public AiMemoryEvaluationStatus getStatus() { return status; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public int getTotalCases() { return totalCases; }
  public int getPassedCases() { return passedCases; }
  public int getFailedCases() { return failedCases; }
  public BigDecimal getAverageScore() { return averageScore; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
}
