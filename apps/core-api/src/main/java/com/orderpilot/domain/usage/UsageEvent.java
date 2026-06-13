package com.orderpilot.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * OP-CAP-16B Usage Metering Foundation — an append-only record of one quota-relevant activity for a
 * tenant.
 *
 * <p>Append-only: the service never updates or deletes events in normal use. {@code units} is a
 * {@code long} to avoid overflow on accumulated/large workloads. {@code metadataJson} is a bounded,
 * sanitized JSON object built from safe routing/decision tokens only — it never contains raw
 * customer message, document, prompt, AI-output, or PII text.
 */
@Entity
@Table(name = "usage_event")
public class UsageEvent {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 40)
  private UsageEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "metric_type", nullable = false, length = 40)
  private UsageMetricType metricType;

  // Stored as the Stage 16A AiWorkloadType.name() token (application-layer enum); plain string to
  // keep the domain free of an application-layer dependency.
  @Column(name = "workload_type", length = 40)
  private String workloadType;

  // Stored as the Stage 16A ModelTier.name() token; plain string for the same reason.
  @Column(name = "model_tier", length = 20)
  private String modelTier;

  @Column(name = "units", nullable = false)
  private long units;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 40)
  private UsageSource source;

  @Column(name = "source_ref", length = 180)
  private String sourceRef;

  @Column(name = "idempotency_key", length = 180)
  private String idempotencyKey;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", columnDefinition = "jsonb")
  private String metadataJson;

  protected UsageEvent() {}

  public UsageEvent(
      UUID tenantId,
      UsageEventType eventType,
      UsageMetricType metricType,
      String workloadType,
      String modelTier,
      long units,
      UsageSource source,
      String sourceRef,
      String idempotencyKey,
      String metadataJson,
      Instant occurredAt,
      Instant createdAt) {
    this.tenantId = tenantId;
    this.eventType = eventType;
    this.metricType = metricType;
    this.workloadType = workloadType;
    this.modelTier = modelTier;
    this.units = units;
    this.source = source;
    this.sourceRef = sourceRef;
    this.idempotencyKey = idempotencyKey;
    this.metadataJson = metadataJson;
    this.occurredAt = occurredAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UsageEventType getEventType() {
    return eventType;
  }

  public UsageMetricType getMetricType() {
    return metricType;
  }

  public String getWorkloadType() {
    return workloadType;
  }

  public String getModelTier() {
    return modelTier;
  }

  public long getUnits() {
    return units;
  }

  public UsageSource getSource() {
    return source;
  }

  public String getSourceRef() {
    return sourceRef;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getMetadataJson() {
    return metadataJson;
  }
}
