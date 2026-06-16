package com.orderpilot.domain.payment;

import java.math.BigDecimal;

/**
 * OP-CAP-17C Payment Obligation Intelligence Foundation.
 *
 * Bounded per-status aggregation projection for a single tenant + counterparty (at most one row per
 * {@link PaymentObligationStatus}). Used to build the customer payment summary without scanning or
 * returning full obligation history.
 */
public interface PaymentObligationStatusAggregate {
  PaymentObligationStatus getStatus();

  long getObligationCount();

  BigDecimal getTotalAmount();

  BigDecimal getPaidAmount();

  BigDecimal getRemainingAmount();
}
