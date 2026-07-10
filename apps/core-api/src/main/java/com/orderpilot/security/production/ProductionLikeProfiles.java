package com.orderpilot.security.production;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Shared production-like Spring profile names used by startup configuration guards. */
public final class ProductionLikeProfiles {

  public static final Set<String> PRODUCTION_LIKE_PROFILES =
      Set.of("prod", "production", "cloud", "staging");

  private ProductionLikeProfiles() {}

  public static boolean isActive(Environment environment) {
    if (environment == null) {
      return false;
    }
    return Arrays.stream(environment.getActiveProfiles())
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .anyMatch(PRODUCTION_LIKE_PROFILES::contains);
  }
}
