package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.common.errors.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LegacyWebhookIngressGuardTest {
  @Test
  void legacyIngressIsAllowedOnlyForExplicitLocalDevOrTestProfiles() {
    assertThatNoException().isThrownBy(() -> guard("local").requireLocalOrTest());
    assertThatNoException().isThrownBy(() -> guard("dev").requireLocalOrTest());
    assertThatNoException().isThrownBy(() -> guard("test").requireLocalOrTest());
  }

  @Test
  void legacyIngressFailsClosedWithoutProfileOrOutsideLocalTest() {
    assertThatThrownBy(() -> new LegacyWebhookIngressGuard(new MockEnvironment()).requireLocalOrTest())
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Webhook route not found");
    assertThatThrownBy(() -> guard("prod").requireLocalOrTest())
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Webhook route not found");
    assertThatThrownBy(() -> guard("staging").requireLocalOrTest())
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Webhook route not found");
  }

  private static LegacyWebhookIngressGuard guard(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return new LegacyWebhookIngressGuard(environment);
  }
}
