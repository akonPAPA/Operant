package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {
  @Test
  void whatsappVerifierReportsStage10ENotConfiguredWithoutClaimingProductionVerification() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier();

    var result = verifier.verify(Map.of(), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
    assertThat(result.providerName()).isEqualTo("WHATSAPP");
  }

  @Test
  void fixtureModeIsExplicitlyReported() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier();

    var result = verifier.verify(Map.of("X-OrderPilot-Fixture-Mode", "true"), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.DISABLED_FIXTURE_MODE);
  }

  @Test
  void requiredProductionVerificationFailsSafelyWhenNotConfigured() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier();

    var result = verifier.verify(Map.of("X-OrderPilot-Require-Signature", "true"), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
  }

  @Test
  void telegramVerifierHasStage10EContractMode() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier();

    var result = verifier.verify(Map.of(), "{}", ChannelType.TELEGRAM, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
    assertThat(result.providerName()).isEqualTo("TELEGRAM");
  }
}
