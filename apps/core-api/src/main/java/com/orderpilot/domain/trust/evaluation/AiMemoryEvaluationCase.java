package com.orderpilot.domain.trust.evaluation;

import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
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
 * A single deterministic, tenant-scoped evaluation case. It describes a bounded advisory-retrieval probe
 * (task type, namespace, optional lookup key) plus the expectation to assert. It stores only memory keys
 * and thresholds — never raw content.
 */
@Entity
@Table(name = "ai_memory_evaluation_case")
public class AiMemoryEvaluationCase {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "run_id") private UUID runId;

  @Enumerated(EnumType.STRING)
  @Column(name = "case_type", nullable = false, length = 48) private AiMemoryEvaluationCaseType caseType;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 48) private AiAdvisoryTaskType taskType;

  @Enumerated(EnumType.STRING)
  @Column(name = "namespace", nullable = false, length = 48) private AiMemoryNamespace namespace;

  @Column(name = "lookup_key", length = 160) private String lookupKey;

  @Column(name = "expected_memory_key", length = 160) private String expectedMemoryKey;

  @Column(name = "expected_excluded_memory_key", length = 160) private String expectedExcludedMemoryKey;

  @Column(name = "min_expected_score") private Integer minExpectedScore;

  @Column(name = "max_results", nullable = false) private int maxResults;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private AiMemoryEvaluationStatus status;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiMemoryEvaluationCase() {}

  public AiMemoryEvaluationCase(UUID tenantId, UUID runId, AiMemoryEvaluationCaseType caseType,
      AiAdvisoryTaskType taskType, AiMemoryNamespace namespace, String lookupKey, String expectedMemoryKey,
      String expectedExcludedMemoryKey, Integer minExpectedScore, int maxResults, Instant now) {
    this.tenantId = tenantId;
    this.runId = runId;
    this.caseType = caseType;
    this.taskType = taskType;
    this.namespace = namespace;
    this.lookupKey = lookupKey;
    this.expectedMemoryKey = expectedMemoryKey;
    this.expectedExcludedMemoryKey = expectedExcludedMemoryKey;
    this.minExpectedScore = minExpectedScore;
    this.maxResults = maxResults;
    this.status = AiMemoryEvaluationStatus.PENDING;
    this.createdAt = now;
  }

  public void markStatus(AiMemoryEvaluationStatus status) {
    this.status = status;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getRunId() { return runId; }
  public AiMemoryEvaluationCaseType getCaseType() { return caseType; }
  public AiAdvisoryTaskType getTaskType() { return taskType; }
  public AiMemoryNamespace getNamespace() { return namespace; }
  public String getLookupKey() { return lookupKey; }
  public String getExpectedMemoryKey() { return expectedMemoryKey; }
  public String getExpectedExcludedMemoryKey() { return expectedExcludedMemoryKey; }
  public Integer getMinExpectedScore() { return minExpectedScore; }
  public int getMaxResults() { return maxResults; }
  public AiMemoryEvaluationStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}
