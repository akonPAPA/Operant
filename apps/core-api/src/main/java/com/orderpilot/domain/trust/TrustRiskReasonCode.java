package com.orderpilot.domain.trust;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Closed taxonomy of deterministic reason codes that drive a {@link TrustRiskDecision}. Each code
 * maps to one explainable rule. Codes are risk indicators only — none of them asserts that a document
 * is fake or that a counterparty is committing fraud. Each code carries the forced minimum risk floor
 * it implies (or {@code null} when it only contributes to the weighted score).
 */
public enum TrustRiskReasonCode {
  DOCUMENT_HIGH_RISK_SIGNAL(TrustRiskLevel.HIGH),
  DOCUMENT_CRITICAL_SIGNAL(TrustRiskLevel.CRITICAL),
  BANK_ACCOUNT_HOLDER_MISMATCH(TrustRiskLevel.HIGH),
  BANK_ACCOUNT_CHANGED_FROM_HISTORY(null),
  DOCUMENT_DATE_FUTURE_FORCE_HIGH(TrustRiskLevel.HIGH),
  DUPLICATE_DOCUMENT_WITH_DIFFERENT_AMOUNT(TrustRiskLevel.HIGH),
  COUNTERPARTY_LOW_TRUST(null),
  COUNTERPARTY_NEW_HIGH_VALUE(null),
  PAYMENT_OVERDUE(null),
  PAYMENT_PARTIAL_OPEN(null),
  PAYMENT_AMOUNT_MISMATCH(TrustRiskLevel.HIGH),
  UNMATCHED_PAYMENT(null),
  OUTSTANDING_BALANCE_HIGH(TrustRiskLevel.HIGH),
  HIGH_VALUE_REQUIRES_APPROVAL(null),
  COUNTERPARTY_NEW_HIGH_VALUE_BANK_MISMATCH(TrustRiskLevel.CRITICAL),
  CROSS_TENANT_REFERENCE(TrustRiskLevel.CRITICAL),
  MANUAL_OVERRIDE_APPLIED(null),
  TENANT_POLICY_FORCED_APPROVAL(TrustRiskLevel.HIGH);

  private final TrustRiskLevel forcedFloor;

  TrustRiskReasonCode(TrustRiskLevel forcedFloor) {
    this.forcedFloor = forcedFloor;
  }

  /** The minimum risk level this reason forces, or {@code null} when it only adds weighted score. */
  public TrustRiskLevel forcedFloor() {
    return forcedFloor;
  }
}
