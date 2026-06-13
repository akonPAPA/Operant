package com.orderpilot.domain.trust;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Provenance of a counterparty trust signal or snapshot. Only {@code DOCUMENT_TRUST_RUN},
 * {@code MANUAL_OVERRIDE}, {@code SYSTEM_RECALC}, and {@code IMPORTED_BASELINE} have producers in this
 * stage; {@code PAYMENT_SIGNAL} and {@code ORDER_HISTORY} are reserved placeholders for later stages
 * (17C / 17D) and are not produced yet.
 */
public enum CounterpartyTrustSourceType {
  DOCUMENT_TRUST_RUN,
  MANUAL_OVERRIDE,
  PAYMENT_SIGNAL,
  ORDER_HISTORY,
  IMPORTED_BASELINE,
  SYSTEM_RECALC
}
