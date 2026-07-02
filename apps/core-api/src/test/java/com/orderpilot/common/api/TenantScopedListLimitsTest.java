package com.orderpilot.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantScopedListLimitsTest {
  @Test
  void clampNormalizesInvalidAndCapsOverMax() {
    assertThat(TenantScopedListLimits.clamp(null, 25, 100)).isEqualTo(25);
    assertThat(TenantScopedListLimits.clamp(0, 25, 100)).isEqualTo(25);
    assertThat(TenantScopedListLimits.clamp(500, 25, 100)).isEqualTo(100);
    assertThat(TenantScopedListLimits.clamp(40, 25, 100)).isEqualTo(40);
  }
}
