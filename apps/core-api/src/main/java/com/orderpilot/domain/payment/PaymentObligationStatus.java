package com.orderpilot.domain.payment;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Deterministic lifecycle status of a tenant-scoped payment obligation. This is an internal,
 * operator-facing receivable state — never a bank/PSP settlement state. {@code CANCELLED} and
 * {@code WRITTEN_OFF} are terminal: deterministic recalculation never auto-mutates them back into an
 * active state. {@code DISPUTED} is preserved until explicitly resolved.
 */
public enum PaymentObligationStatus {
  OPEN,
  PARTIALLY_PAID,
  PAID,
  OVERDUE,
  DISPUTED,
  CANCELLED,
  WRITTEN_OFF;

  /** Terminal states that deterministic recalculation must never auto-change. */
  public boolean isClosedTerminal() {
    return this == CANCELLED || this == WRITTEN_OFF;
  }
}
