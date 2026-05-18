package com.orderpilot.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {
  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void storesTenantForCurrentThread() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
  }

  @Test
  void failsWhenTenantIsMissing() {
    assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(TenantContextMissingException.class);
  }
}