package com.orderpilot.security.policy;

public class TenantPolicyException extends RuntimeException {
  public TenantPolicyException(String message) {
    super(message);
  }
}
