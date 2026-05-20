package com.orderpilot.application.services.workspace;

public class QuoteLifecycleViolation extends RuntimeException {
  public QuoteLifecycleViolation(String message) {
    super(message);
  }
}
