package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {
  @Test
  void localDevelopmentModeIsExplicitAndAccepted() {
    var connection = new ChannelConnection(UUID.randomUUID(), ChannelProviderType.TELEGRAM, "Telegram", null, null, null, Instant.now());
    connection.configureWebhookVerificationMode("DISABLED_FOR_LOCAL_DEV", Instant.now());

    VerificationResult result = new TelegramWebhookVerifier().verify(connection, Map.of(), "{}");

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("SKIPPED_LOCAL_DEV");
  }

  @Test
  void signatureModeRejectsMissingSignature() {
    var connection = new ChannelConnection(UUID.randomUUID(), ChannelProviderType.WHATSAPP, "WhatsApp", null, null, "vault-ref", Instant.now());
    connection.configureWebhookVerificationMode("SIGNATURE_HEADER", Instant.now());

    VerificationResult result = new WhatsAppWebhookVerifier().verify(connection, Map.of(), "{}");

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
  }
}
