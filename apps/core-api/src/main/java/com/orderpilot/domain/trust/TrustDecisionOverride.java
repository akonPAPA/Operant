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
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Append-only audit record of a manual override of a {@link TrustRiskDecision}. The original decision
 * and its signal contributions are never deleted — this row captures the before/after level and action,
 * the required reason, and the actor. A critical decision must never be silently downgraded; downgrades
 * out of CRITICAL are constrained by the service.
 */
@Entity
@Table(name = "trust_decision_override")
public class TrustDecisionOverride {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "trust_risk_decision_id", nullable = false) private UUID trustRiskDecisionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_risk_level", nullable = false, length = 16) private TrustRiskLevel previousRiskLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_risk_level", nullable = false, length = 16) private TrustRiskLevel newRiskLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_action", nullable = false, length = 32) private TrustRiskAction previousAction;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_action", nullable = false, length = 32) private TrustRiskAction newAction;

  /** Bounded, required justification. Never raw document/OCR/prompt text. */
  @Column(name = "reason", nullable = false, length = 280) private String reason;

  @Column(name = "overridden_by") private UUID overriddenBy;

  @Column(name = "overridden_at", nullable = false) private Instant overriddenAt;

  @Column(name = "audit_event_id") private UUID auditEventId;

  protected TrustDecisionOverride() {}

  public TrustDecisionOverride(UUID tenantId, UUID trustRiskDecisionId, TrustRiskLevel previousRiskLevel,
      TrustRiskLevel newRiskLevel, TrustRiskAction previousAction, TrustRiskAction newAction,
      String reason, UUID overriddenBy, UUID auditEventId, Instant overriddenAt) {
    this.tenantId = tenantId;
    this.trustRiskDecisionId = trustRiskDecisionId;
    this.previousRiskLevel = previousRiskLevel;
    this.newRiskLevel = newRiskLevel;
    this.previousAction = previousAction;
    this.newAction = newAction;
    this.reason = reason;
    this.overriddenBy = overriddenBy;
    this.auditEventId = auditEventId;
    this.overriddenAt = overriddenAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getTrustRiskDecisionId() { return trustRiskDecisionId; }
  public TrustRiskLevel getPreviousRiskLevel() { return previousRiskLevel; }
  public TrustRiskLevel getNewRiskLevel() { return newRiskLevel; }
  public TrustRiskAction getPreviousAction() { return previousAction; }
  public TrustRiskAction getNewAction() { return newAction; }
  public String getReason() { return reason; }
  public UUID getOverriddenBy() { return overriddenBy; }
  public Instant getOverriddenAt() { return overriddenAt; }
  public UUID getAuditEventId() { return auditEventId; }
}
