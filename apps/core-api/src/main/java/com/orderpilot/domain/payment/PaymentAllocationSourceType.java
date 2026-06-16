package com.orderpilot.domain.payment;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Provenance of a payment allocation applied to an obligation. This is a safe internal/mirrored
 * allocation record — never a real bank transaction or PSP payload store. {@code PAYMENT_MIRROR}
 * references a mirrored internal id only.
 */
public enum PaymentAllocationSourceType {
  MANUAL,
  DEMO_IMPORT,
  PAYMENT_MIRROR,
  SYSTEM_ADJUSTMENT
}
