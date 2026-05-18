package com.orderpilot.common.tenant;

public class TenantContextMissingException extends RuntimeException {
  public TenantContextMissingException(String message) {
    super(message);
  }
}