package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramWebhookVerifierTest {
  @Test
  void demoFixtureModeAllowsTelegramWebhookWithoutRealToken() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier("");

    WebhookSignatureVerificationResult result = verifier.verify(Map.of("X-OrderPilot-Fixture-Mode", "true"), "{}", ChannelType.TELEGRAM, null);

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.DISABLED_FIXTURE_MODE);
  }

  @Test
  void configuredSecretRejectsInvalidTelegramHeader() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier("configured-secret");

    WebhookSignatureVerificationResult result = verifier.verify(Map.of("x-telegram-bot-api-secret-token", "wrong"), "{}", ChannelType.TELEGRAM, null);

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
  }

  @Test
  void configuredSecretAcceptsMatchingTelegramHeader() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier("configured-secret");

    WebhookSignatureVerificationResult result = verifier.verify(Map.of("x-telegram-bot-api-secret-token", "configured-secret"), "{}", ChannelType.TELEGRAM, null);

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.PROVIDER_SPECIFIC);
  }
}
