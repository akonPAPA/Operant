package com.orderpilot.domain.trust.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * The deterministic outcome of executing one evaluation case. Records only the top-hint metadata, boolean
 * assertions, and a bounded failure reason — never raw content. Tenant-scoped.
 */
@Entity
@Table(name = "ai_memory_evaluation_result")
public class AiMemoryEvaluationResult {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "run_id", nullable = false) private UUID runId;

  @Column(name = "case_id", nullable = false) private UUID caseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private AiMemoryEvaluationStatus status;

  @Column(name = "top_memory_record_id") private UUID topMemoryRecordId;

  @Column(name = "top_memory_key", length = 160) private String topMemoryKey;

  @Column(name = "top_score") private Integer topScore;

  @Column(name = "expected_matched", nullable = false) private boolean expectedMatched;

  @Column(name = "excluded_unsafe", nullable = false) private boolean excludedUnsafe;

  @Column(name = "tenant_isolated", nullable = false) private boolean tenantIsolated;

  @Column(name = "failure_reason", length = 280) private String failureReason;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiMemoryEvaluationResult() {}

  public AiMemoryEvaluationResult(UUID tenantId, UUID runId, UUID caseId, AiMemoryEvaluationStatus status,
      UUID topMemoryRecordId, String topMemoryKey, Integer topScore, boolean expectedMatched,
      boolean excludedUnsafe, boolean tenantIsolated, String failureReason, Instant now) {
    this.tenantId = tenantId;
    this.runId = runId;
    this.caseId = caseId;
    this.status = status;
    this.topMemoryRecordId = topMemoryRecordId;
    this.topMemoryKey = topMemoryKey;
    this.topScore = topScore;
    this.expectedMatched = expectedMatched;
    this.excludedUnsafe = excludedUnsafe;
    this.tenantIsolated = tenantIsolated;
    this.failureReason = failureReason;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getRunId() { return runId; }
  public UUID getCaseId() { return caseId; }
  public AiMemoryEvaluationStatus getStatus() { return status; }
  public UUID getTopMemoryRecordId() { return topMemoryRecordId; }
  public String getTopMemoryKey() { return topMemoryKey; }
  public Integer getTopScore() { return topScore; }
  public boolean isExpectedMatched() { return expectedMatched; }
  public boolean isExcludedUnsafe() { return excludedUnsafe; }
  public boolean isTenantIsolated() { return tenantIsolated; }
  public String getFailureReason() { return failureReason; }
  public Instant getCreatedAt() { return createdAt; }
}
