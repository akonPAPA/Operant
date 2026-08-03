package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WebhookVerificationStartupGuardTest {
  @Test
  void productionFixtureModeFailsStartup() {
    WebhookVerificationAuthority authority = WebhookVerificationAuthority.forTests(true, true);
    WebhookVerificationStartupGuard guard =
        new WebhookVerificationStartupGuard(authority, "whatsapp-secret", "telegram-secret");

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixture-mode-enabled");
  }

  @Test
  void productionMissingWhatsAppSecretFailsStartup() {
    WebhookVerificationAuthority authority = WebhookVerificationAuthority.forTests(false, true);
    WebhookVerificationStartupGuard guard =
        new WebhookVerificationStartupGuard(authority, "", "telegram-secret");

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("whatsapp.app-secret");
  }

  @Test
  void productionMissingTelegramSecretFailsStartup() {
    WebhookVerificationAuthority authority = WebhookVerificationAuthority.forTests(false, true);
    WebhookVerificationStartupGuard guard =
        new WebhookVerificationStartupGuard(authority, "whatsapp-secret", " ");

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("webhook-secret-token");
  }

  @Test
  void nonProductionAllowsBlankSecretsWithoutFixture() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    WebhookVerificationAuthority authority = new WebhookVerificationAuthority(environment, false);
    WebhookVerificationStartupGuard guard = new WebhookVerificationStartupGuard(authority, "", "");

    guard.afterPropertiesSet();
  }
}
