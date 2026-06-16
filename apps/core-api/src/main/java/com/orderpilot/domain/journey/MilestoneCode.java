package com.orderpilot.domain.journey;

/**
 * OP-CAP-22 — compact canonical milestone set for the commercial transaction lifecycle.
 *
 * <p>{@code sortOrder} drives deterministic timeline ordering. {@code customerVisibleDefault} marks
 * which milestones are safe to expose on a future customer-facing tracking surface (internal-only
 * steps such as validation/drafting are not). Payment milestones exist in the model but are derived
 * as UNKNOWN until a real payment mirror domain is wired — they are never fabricated.
 */
public enum MilestoneCode {
  REQUEST_RECEIVED("Request received", 10, true),
  VALIDATION_STARTED("Validation started", 20, false),
  VALIDATION_COMPLETED("Validation completed", 30, false),
  QUOTE_DRAFTED("Quote drafted", 40, false),
  QUOTE_SENT("Quote sent", 50, true),
  QUOTE_APPROVED("Quote approved", 60, true),
  ORDER_DRAFTED("Order drafted", 70, false),
  ORDER_CONFIRMED("Order confirmed", 80, true),
  PAYMENT_PENDING("Payment pending", 90, true),
  PAYMENT_CONFIRMED("Payment confirmed", 100, true),
  FULFILLMENT_PREPARING("Preparing fulfillment", 110, true),
  PACKED("Packed", 120, true),
  READY_TO_SHIP("Ready to ship", 130, true),
  SHIPPED("Shipped", 140, true),
  DELIVERED("Delivered", 150, true),
  CLOSED("Closed", 160, true),
  CANCELLED("Cancelled", 170, true),
  BLOCKED_EXCEPTION("Blocked / exception", 180, false);

  private final String label;
  private final int sortOrder;
  private final boolean customerVisibleDefault;

  MilestoneCode(String label, int sortOrder, boolean customerVisibleDefault) {
    this.label = label;
    this.sortOrder = sortOrder;
    this.customerVisibleDefault = customerVisibleDefault;
  }

  public String label() {
    return label;
  }

  public int sortOrder() {
    return sortOrder;
  }

  public boolean customerVisibleDefault() {
    return customerVisibleDefault;
  }
}
