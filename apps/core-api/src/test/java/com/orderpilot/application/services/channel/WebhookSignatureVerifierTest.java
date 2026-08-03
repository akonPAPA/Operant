package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {
  @Test
  void whatsappMissingSecretFailsClosed() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier();

    var result = verifier.verify(Map.of(), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertThat(result.providerName()).isEqualTo("WHATSAPP");
  }

  @Test
  void forgedFixtureHeaderDoesNotBypassWhatsApp() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier();

    var result =
        verifier.verify(
            Map.of("X-OrderPilot-Fixture-Mode", "true"),
            "{}",
            ChannelType.WHATSAPP,
            UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
  }

  @Test
  void serverOwnedFixtureModeAllowsUnsignedWhatsAppWhenSecretAbsent() {
    WhatsAppSignatureVerifier verifier =
        new WhatsAppSignatureVerifier("", WebhookVerificationAuthority.forTests(true, false));

    var result = verifier.verify(Map.of(), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.DISABLED_FIXTURE_MODE);
  }

  @Test
  void telegramMissingSecretFailsClosed() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier("");

    var result = verifier.verify(Map.of(), "{}", ChannelType.TELEGRAM, null);

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertThat(result.providerName()).isEqualTo("TELEGRAM");
  }

  @Test
  void forgedFixtureHeaderDoesNotBypassTelegram() {
    TelegramSecretTokenVerifier verifier = new TelegramSecretTokenVerifier("");

    var result =
        verifier.verify(
            Map.of("X-OrderPilot-Fixture-Mode", "true"), "{}", ChannelType.TELEGRAM, null);

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
  }

  @Test
  void configuredWhatsAppSecretRejectsMissingSignature() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier("configured-secret");

    var result = verifier.verify(Map.of(), "{}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
  }
}
