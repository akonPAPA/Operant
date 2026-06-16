package com.orderpilot.domain.payment;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Provenance of a payment obligation. All sources are internal/mirrored — no real bank or PSP
 * settlement source exists in this stage. {@code INVOICE_MIRROR}/{@code ORDER_MIRROR} reference a
 * mirrored internal id only (never a raw external payload).
 */
public enum PaymentObligationSourceType {
  MANUAL,
  DEMO_IMPORT,
  INVOICE_MIRROR,
  ORDER_MIRROR,
  SYSTEM_GENERATED
}
