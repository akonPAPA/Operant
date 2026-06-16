package com.orderpilot.domain.payment;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Append-only event taxonomy for payment obligation state transitions. Used as bounded audit/read
 * evidence — never carries raw bank/PSP payloads or sensitive values.
 */
public enum PaymentObligationEventType {
  OBLIGATION_CREATED,
  PAYMENT_ALLOCATED,
  PAYMENT_REVERSED,
  STATUS_RECALCULATED,
  MARKED_DISPUTED,
  DISPUTE_RESOLVED,
  CANCELLED,
  WRITTEN_OFF,
  OVERDUE_DETECTED
}
