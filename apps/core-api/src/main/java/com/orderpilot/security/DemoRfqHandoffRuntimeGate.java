package com.orderpilot.security;

import com.orderpilot.security.policy.TenantPolicyException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed runtime gate for the local/demo RFQ entrypoint.
 *
 * <p>The endpoint is available only when it is explicitly enabled and an explicit local/demo/test
 * profile is active. Any production-like profile wins over an allowed profile so mixed profile
 * configuration cannot accidentally enable the endpoint.
 */
@Component
public class DemoRfqHandoffRuntimeGate {
  private static final Set<String> ALLOWED_PROFILES = Set.of("local", "dev", "demo", "test");
  private static final Set<String> PRODUCTION_LIKE_PROFILES =
      Set.of("prod", "production", "cloud", "staging");

  private final boolean enabled;
  private final Set<String> activeProfiles;

  public DemoRfqHandoffRuntimeGate(
      @Value("${orderpilot.demo.rfq-handoff.enabled:false}") boolean enabled,
      Environment environment) {
    this.enabled = enabled;
    this.activeProfiles =
        environment == null
            ? Set.of()
            : Arrays.stream(environment.getActiveProfiles())
                .map(DemoRfqHandoffRuntimeGate::normalize)
                .filter(profile -> !profile.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public void requireEnabled() {
    boolean productionLike =
        activeProfiles.stream().anyMatch(PRODUCTION_LIKE_PROFILES::contains);
    boolean explicitlyLocalDemo =
        activeProfiles.stream().anyMatch(ALLOWED_PROFILES::contains);
    if (!enabled || productionLike || !explicitlyLocalDemo) {
      throw new TenantPolicyException("Local demo RFQ entrypoint is disabled");
    }
  }

  private static String normalize(String profile) {
    return profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
  }
}
