package com.orderpilot.application.services.workspace;

public class QuoteHandoffViolation extends RuntimeException {
  public QuoteHandoffViolation(String message) {
    super(message);
  }
}
