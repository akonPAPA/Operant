package com.orderpilot.security.policy;

import java.util.UUID;

public record TenantPolicyDecision(
    boolean allowed,
    String reasonCode,
    String message,
    UUID tenantId,
    TenantPolicyAction action) {
  public static TenantPolicyDecision allow(TenantPolicyContext context, String reasonCode) {
    return new TenantPolicyDecision(true, reasonCode, "Allowed by tenant policy", context.tenantId(), context.action());
  }

  public static TenantPolicyDecision deny(TenantPolicyContext context, String reasonCode, String message) {
    return new TenantPolicyDecision(false, reasonCode, message, context == null ? null : context.tenantId(), context == null ? null : context.action());
  }
}
