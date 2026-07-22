package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * P1-E2A - structural principal-class separation at credential-configuration time. A single control
 * credential may hold the STAFF_CONTROL_* family OR the CONTROL_EXECUTOR_* family, but never both, so
 * "staff credentials must not act as executors" (and the converse) is a config invariant, not merely a
 * route-mapping property. A tenant business permission is never a valid control-credential permission.
 *
 * <p>The strict configuration path is exercised directly ({@code strict = true}); the lenient runtime
 * registry deliberately fails closed to an empty credential rather than throwing, which the production
 * configuration validator surfaces at startup.
 */
class ControlPlaneLifecycleCredentialSeparationTest {
  private static final String SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  private static final String AUDIENCE = "orderpilot-control-plane";
  private static final String VALID_FROM = "2026-01-01T00:00:00Z";
  private static final String EXPIRES_AT = "2035-01-01T00:00:00Z";
  private static final String KEY_VERSION = "control-v1";
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void staffOnlyControlCredentialIsAccepted() {
    assertThat(validate("STAFF_CONTROL_BACKUP,STAFF_CONTROL_LIFECYCLE_READ")).isPresent();
  }

  @Test
  void executorOnlyControlCredentialIsAccepted() {
    assertThat(validate("CONTROL_EXECUTOR_LEASE,CONTROL_EXECUTOR_REPORT")).isPresent();
  }

  @Test
  void mixedStaffAndExecutorCredentialIsRejected() {
    assertThatThrownBy(() -> validate("STAFF_CONTROL_BACKUP,CONTROL_EXECUTOR_LEASE"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validate("CONTROL_EXECUTOR_REPORT,STAFF_CONTROL_LIFECYCLE_READ"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tenantBusinessPermissionIsNotAValidControlCredentialPermission() {
    assertThatThrownBy(() -> validate("QUOTE_READ"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validate("STAFF_CONTROL_BACKUP,ANALYTICS_READ"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static java.util.Optional<?> validate(String permissions) {
    return ControlPlaneCredentialConfigurationValidator.validatedRecord(
        "ops-prod",
        SECRET,
        AUDIENCE,
        "ENABLED",
        VALID_FROM,
        EXPIRES_AT,
        false,
        permissions,
        KEY_VERSION,
        null,
        CLOCK,
        true);
  }
}
