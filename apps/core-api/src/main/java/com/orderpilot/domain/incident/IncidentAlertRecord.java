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
 * OP-CAP-53 — a record-only alert/notification foundation. It records that a security/platform alert SHOULD
 * be emitted for an incident-response event (critical incident created, break-glass requested/approved/
 * rejected/revoked/expired). This stage performs NO external delivery: there is no email/SMS/Slack transport
 * and no network call. The {@code detail} field is a short, backend-built, operator-safe summary — never a
 * secret, credential, token, or raw payload.
 */
@Entity
@Table(name = "incident_alert_record")
public class IncidentAlertRecord {
  public static final int MAX_DETAIL_LENGTH = 500;

  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id") private UUID tenantId;
  @Column(name = "incident_id", nullable = false) private UUID incidentId;
  @Column(name = "break_glass_request_id") private UUID breakGlassRequestId;
  @Enumerated(EnumType.STRING)
  @Column(name = "alert_type", nullable = false, length = 40) private AlertType alertType;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private AlertStatus status;
  @Column(name = "detail", length = MAX_DETAIL_LENGTH) private String detail;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected IncidentAlertRecord() {}

  public IncidentAlertRecord(
      UUID tenantId,
      UUID incidentId,
      UUID breakGlassRequestId,
      AlertType alertType,
      String detail,
      Instant now) {
    this.tenantId = tenantId;
    this.incidentId = incidentId;
    this.breakGlassRequestId = breakGlassRequestId;
    this.alertType = alertType;
    this.status = AlertStatus.PENDING_DISPATCH;
    this.detail = detail;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getIncidentId() { return incidentId; }
  public UUID getBreakGlassRequestId() { return breakGlassRequestId; }
  public AlertType getAlertType() { return alertType; }
  public AlertStatus getStatus() { return status; }
  public String getDetail() { return detail; }
  public Instant getCreatedAt() { return createdAt; }
}
