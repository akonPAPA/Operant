package com.orderpilot.domain.incident;

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
 * OP-CAP-53 — an audit-backed incident record. An incident captures a bounded title + reason, a severity and
 * a bounded {@link IncidentType}, and a backend-owned lifecycle ({@code OPEN} → {@code CLOSED}). It records
 * NO business truth and mutates NO order/quote/inventory/customer/price row — it is purely an
 * incident-response control object.
 *
 * <ul>
 *   <li>{@code title} and {@code reason} are required (no anonymous incident);</li>
 *   <li>a {@link IncidentSeverity#CRITICAL} incident can never be closed without a closure reason;</li>
 *   <li>{@code status}, timestamps, and the creating staff actor are backend-owned — never client-supplied;</li>
 *   <li>{@code tenantId} is the incident's tenant scope (set from the trusted tenant context).</li>
 * </ul>
 */
@Entity
@Table(name = "incident_record")
public class IncidentRecord {
  public static final int MAX_TITLE_LENGTH = 200;
  public static final int MAX_REASON_LENGTH = 2000;
  public static final int MAX_CLOSURE_REASON_LENGTH = 1000;

  @Id @GeneratedValue private UUID id;
  /** The incident's tenant scope. Nullable is reserved for a future explicitly platform-wide incident. */
  @Column(name = "tenant_id") private UUID tenantId;
  @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH) private String title;
  @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH) private String reason;
  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20) private IncidentSeverity severity;
  @Enumerated(EnumType.STRING)
  @Column(name = "incident_type", nullable = false, length = 40) private IncidentType incidentType;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private IncidentStatus status;
  @Column(name = "created_by_staff_actor") private UUID createdByStaffActor;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "closed_at") private Instant closedAt;
  @Column(name = "closure_reason", length = MAX_CLOSURE_REASON_LENGTH) private String closureReason;

  protected IncidentRecord() {}

  public IncidentRecord(
      UUID tenantId,
      String title,
      String reason,
      IncidentSeverity severity,
      IncidentType incidentType,
      UUID createdByStaffActor,
      Instant now) {
    this.tenantId = tenantId;
    this.title = title;
    this.reason = reason;
    this.severity = severity;
    this.incidentType = incidentType;
    this.status = IncidentStatus.OPEN;
    this.createdByStaffActor = createdByStaffActor;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public boolean isClosed() {
    return status.isClosed();
  }

  public boolean isCritical() {
    return severity != null && severity.isCritical();
  }

  /**
   * Close the incident. A CRITICAL incident requires a non-null closure reason — the caller validates this
   * before calling. Closing an already-closed incident is a conflict the caller must reject.
   */
  public void close(String closureReason, Instant now) {
    if (isClosed()) {
      throw new IllegalStateException("Incident is already closed");
    }
    this.status = IncidentStatus.CLOSED;
    this.closureReason = closureReason;
    this.closedAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getTitle() { return title; }
  public String getReason() { return reason; }
  public IncidentSeverity getSeverity() { return severity; }
  public IncidentType getIncidentType() { return incidentType; }
  public IncidentStatus getStatus() { return status; }
  public UUID getCreatedByStaffActor() { return createdByStaffActor; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getClosedAt() { return closedAt; }
  public String getClosureReason() { return closureReason; }
}
