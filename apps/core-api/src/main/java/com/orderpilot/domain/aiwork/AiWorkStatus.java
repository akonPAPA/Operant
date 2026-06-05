package com.orderpilot.domain.aiwork;

/**
 * OP-CAP-07A lifecycle of an advisory AI work suggestion.
 *
 * <p>{@code ACCEPTED} means an operator accepted the advisory text/idea only. It does NOT mean a
 * quote/order was approved, a discount/substitute was approved, or any external write occurred.
 */
public enum AiWorkStatus {
  GENERATED,
  ACCEPTED,
  REJECTED
}
