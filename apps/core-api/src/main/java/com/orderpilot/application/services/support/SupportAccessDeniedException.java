package com.orderpilot.application.services.support;

/**
 * OP-CAP-51 — raised when a support access decision fails closed: no grant, expired grant, wrong tenant,
 * wrong/insufficient scope, or an unknown/disabled staff principal. The message is intentionally generic
 * and never reveals which specific condition failed, so a caller learns nothing about another tenant's
 * grant state. Mapped to a stable 403 by the global exception handler.
 */
public class SupportAccessDeniedException extends RuntimeException {
  public SupportAccessDeniedException(String message) {
    super(message);
  }
}
