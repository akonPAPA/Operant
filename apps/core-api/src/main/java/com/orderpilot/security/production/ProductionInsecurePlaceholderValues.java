package com.orderpilot.security.production;

import java.util.Locale;
import java.util.Set;

/** Detects well-known insecure placeholder credential values for production startup rejection. */
public final class ProductionInsecurePlaceholderValues {

  private static final Set<String> EXACT_PLACEHOLDERS =
      Set.of(
          "change-me",
          "change-me-local-dev-only",
          "changeme",
          "your-secret-here",
          "replace-me",
          "dev-only",
          "local-dev-only",
          "placeholder",
          "secret",
          "password");

  private ProductionInsecurePlaceholderValues() {}

  public static boolean isPlaceholder(String value) {
    if (value == null) {
      return true;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return true;
    }
    if (EXACT_PLACEHOLDERS.contains(normalized)) {
      return true;
    }
    return normalized.contains("change-me");
  }

  public static void requireNonPlaceholder(String propertyName, String value) {
    if (isPlaceholder(value)) {
      throw new IllegalStateException(
          propertyName + " must be configured with a non-placeholder secret in production-like profiles");
    }
  }
}
