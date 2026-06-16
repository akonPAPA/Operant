package com.orderpilot.domain.trust;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Origin of a single {@link TrustRiskSignalContribution}. Preserves explainability — every
 * contribution records which upstream subsystem produced it.
 */
public enum TrustRiskSignalSourceType {
  DOCUMENT_TRUST,
  COUNTERPARTY_TRUST,
  PAYMENT_OBLIGATION,
  PAYMENT_ALLOCATION,
  POLICY_RULE,
  MANUAL_OVERRIDE,
  SYSTEM
}
