package com.orderpilot.domain.trust.ai;

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
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Safe, tenant-scoped metadata about one AI/runtime workload — provider/model/prompt-version/token and
 * cost estimates, outcome status, and an optional bounded source pointer. It deliberately has NO column
 * for a raw prompt body, raw model response, secrets, or customer message/document text. Provider-agnostic:
 * this stage never calls an external AI provider; the trace just governs metadata.
 */
@Entity
@Table(name = "ai_runtime_trace")
public class AiRuntimeTrace {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "workload_type", nullable = false, length = 48) private String workloadType;

  @Column(name = "model_provider", length = 48) private String modelProvider;

  @Column(name = "model_name", length = 80) private String modelName;

  @Column(name = "prompt_version", nullable = false, length = 48) private String promptVersion;

  @Column(name = "schema_version", length = 48) private String schemaVersion;

  @Column(name = "input_token_estimate") private Integer inputTokenEstimate;

  @Column(name = "output_token_estimate") private Integer outputTokenEstimate;

  @Column(name = "cost_units", precision = 12, scale = 4) private BigDecimal costUnits;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private AiRuntimeStatus status;

  @Column(name = "failure_code", length = 48) private String failureCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiRuntimeTrace() {}

  public AiRuntimeTrace(UUID tenantId, String workloadType, String modelProvider, String modelName,
      String promptVersion, String schemaVersion, Integer inputTokenEstimate, Integer outputTokenEstimate,
      BigDecimal costUnits, AiRuntimeStatus status, String failureCode, AiMemorySourceType sourceType,
      UUID sourceId, Instant now) {
    this.tenantId = tenantId;
    this.workloadType = workloadType;
    this.modelProvider = modelProvider;
    this.modelName = modelName;
    this.promptVersion = promptVersion;
    this.schemaVersion = schemaVersion;
    this.inputTokenEstimate = inputTokenEstimate;
    this.outputTokenEstimate = outputTokenEstimate;
    this.costUnits = costUnits;
    this.status = status;
    this.failureCode = failureCode;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getWorkloadType() { return workloadType; }
  public String getModelProvider() { return modelProvider; }
  public String getModelName() { return modelName; }
  public String getPromptVersion() { return promptVersion; }
  public String getSchemaVersion() { return schemaVersion; }
  public Integer getInputTokenEstimate() { return inputTokenEstimate; }
  public Integer getOutputTokenEstimate() { return outputTokenEstimate; }
  public BigDecimal getCostUnits() { return costUnits; }
  public AiRuntimeStatus getStatus() { return status; }
  public String getFailureCode() { return failureCode; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public Instant getCreatedAt() { return createdAt; }
}
