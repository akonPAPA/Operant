package com.orderpilot.domain.trust;

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
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * A single deterministic, explainable trust signal produced by a {@link DocumentTrustRun}. Carries
 * bounded evidence metadata ({@code fieldKey}/{@code pageNumber}/{@code evidenceRef}/{@code explanation})
 * only — it never contains raw document text, large OCR text, extracted values, or counterparty
 * identity values.
 */
@Entity
@Table(name = "document_trust_signal")
public class DocumentTrustSignal {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "trust_run_id", nullable = false) private UUID trustRunId;

  @Enumerated(EnumType.STRING)
  @Column(name = "signal_code", nullable = false, length = 48) private TrustSignalCode signalCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16) private TrustSignalSeverity severity;

  /** Stable token naming the document attribute this signal is about. */
  @Column(name = "field_key", length = 64) private String fieldKey;

  /** Optional 1-based page locator; null when not page-specific. */
  @Column(name = "page_number") private Integer pageNumber;

  /** Bounded metadata reference pointer. Never raw text or OCR content. */
  @Column(name = "evidence_ref", length = 120) private String evidenceRef;

  /** Bounded, generic explanation. Never raw document text or extracted values. */
  @Column(name = "explanation", length = 280) private String explanation;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected DocumentTrustSignal() {}

  public DocumentTrustSignal(UUID tenantId, UUID trustRunId, TrustSignalCode signalCode, TrustSignalSeverity severity,
      String fieldKey, Integer pageNumber, String evidenceRef, String explanation, Instant createdAt) {
    this.tenantId = tenantId;
    this.trustRunId = trustRunId;
    this.signalCode = signalCode;
    this.severity = severity;
    this.fieldKey = fieldKey;
    this.pageNumber = pageNumber;
    this.evidenceRef = evidenceRef;
    this.explanation = explanation;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getTrustRunId() { return trustRunId; }
  public TrustSignalCode getSignalCode() { return signalCode; }
  public TrustSignalSeverity getSeverity() { return severity; }
  public String getFieldKey() { return fieldKey; }
  public Integer getPageNumber() { return pageNumber; }
  public String getEvidenceRef() { return evidenceRef; }
  public String getExplanation() { return explanation; }
  public Instant getCreatedAt() { return createdAt; }
}
