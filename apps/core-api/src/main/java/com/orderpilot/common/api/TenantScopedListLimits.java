package com.orderpilot.common.api;

/**
 * Shared clamp helpers for tenant-scoped list endpoints. Keeps defaults/max consistent without a
 * pagination framework.
 */
public final class TenantScopedListLimits {
  public static final int WORKSPACE_PREVIEW_DEFAULT = 25;
  public static final int GENERAL_LIST_DEFAULT = 50;
  public static final int GENERAL_LIST_MAX = 100;
  public static final int AUDIT_PREVIEW_DEFAULT = 20;

  private TenantScopedListLimits() {}

  public static int clamp(Integer limit, int defaultLimit, int maxLimit) {
    if (limit == null || limit < 1) {
      return defaultLimit;
    }
    return Math.min(limit, maxLimit);
  }
}
