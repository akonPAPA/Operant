package com.orderpilot.common.tenant;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {
  private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

  private TenantContext() {
  }

  public static void setTenantId(UUID tenantId) {
    CURRENT_TENANT.set(tenantId);
  }

  public static Optional<UUID> getTenantId() {
    return Optional.ofNullable(CURRENT_TENANT.get());
  }

  public static UUID requireTenantId() {
    return getTenantId().orElseThrow(() -> new TenantContextMissingException("Tenant context is required"));
  }

  public static void clear() {
    CURRENT_TENANT.remove();
  }
}