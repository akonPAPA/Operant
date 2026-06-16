package com.orderpilot.domain.trust;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Closed taxonomy of counterparty-level trust signals. Codes are deterministic, explainable risk
 * indicators only — none asserts fraud. In this stage only the document-derived subset
 * ({@code DOCUMENT_HIGH_RISK_SIGNAL}, {@code DOCUMENT_CRITICAL_RISK_SIGNAL},
 * {@code BANK_ACCOUNT_HOLDER_MISMATCH}, {@code NEW_COUNTERPARTY}, {@code LOW_ORDER_HISTORY}) has a
 * producer; the rest are reserved placeholders for later stages (payment/order/connector derived).
 */
public enum CounterpartySignalCode {
  NEW_COUNTERPARTY,
  LOW_ORDER_HISTORY,
  HIGH_DOCUMENT_ERROR_RATE,
  HIGH_MANUAL_REVIEW_RATE,
  FREQUENT_BANK_ACCOUNT_CHANGE,
  UNUSUAL_PRODUCT_MIX,
  UNUSUAL_REGION_FOR_CUSTOMER,
  ORDER_VALUE_OUTLIER,
  DISPUTE_HISTORY_HIGH,
  PAYMENT_OVERDUE,
  PARTIAL_PAYMENT_OPEN,
  FREQUENT_LATE_PAYMENT,
  DOCUMENT_HIGH_RISK_SIGNAL,
  DOCUMENT_CRITICAL_RISK_SIGNAL,
  BANK_ACCOUNT_CHANGED_FROM_HISTORY,
  BANK_ACCOUNT_HOLDER_MISMATCH
}
