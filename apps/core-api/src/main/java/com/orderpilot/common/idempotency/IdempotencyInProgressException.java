package com.orderpilot.common.idempotency;

public class IdempotencyInProgressException extends RuntimeException {
  public IdempotencyInProgressException(String message) {
    super(message);
  }
}
