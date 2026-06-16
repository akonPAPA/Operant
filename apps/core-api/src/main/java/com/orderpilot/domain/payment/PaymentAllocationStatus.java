package com.orderpilot.domain.payment;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Lifecycle of a single payment allocation. Reversal is deterministic and audited; a reversed
 * allocation is never deleted (append-only evidence).
 */
public enum PaymentAllocationStatus {
  APPLIED,
  REVERSED
}
