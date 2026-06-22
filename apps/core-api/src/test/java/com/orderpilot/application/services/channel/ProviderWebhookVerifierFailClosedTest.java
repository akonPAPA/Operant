package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-42G — provider webhook verifier fail-closed proof (connection-based Path 2).
 *
 * <p>The connection-based provider verifiers ({@link ViberWebhookVerifier}, {@link WeChatWebhookVerifier},
 * {@link MetaMessengerWebhookVerifier}, {@link WhatsAppWebhookVerifier}, {@link TelegramWebhookVerifier})
 * extend {@code AbstractProviderWebhookVerifier}. Real cryptographic provider verification is <b>not</b>
 * implemented for them: the raw secret is intentionally not exposed in this path. Therefore an "enforcing"
 * verification mode ({@code SHARED_SECRET} / {@code SIGNATURE_HEADER} / {@code PROVIDER_SPECIFIC}) must
 * <b>fail closed</b> — it must never return {@code accepted=true} on the mere presence of a hostile,
 * unverified header. The only honest accept is the explicit {@code DISABLED_FOR_LOCAL_DEV} mode.
 *
 * <p>This drives the real verifier classes (no mocks) and asserts the safe boundary for every provider,
 * including a negative-proof that a present-but-unverified hostile header is NOT accepted.
 */
class ProviderWebhookVerifierFailClosedTest {
  private static final Instant NOW = Instant.parse("2026-06-22T00:00:00Z");

  // No verifier rejection reason may leak internal/implementation/secret tokens.
  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "private key", "raw signing secret"
  };

  private static List<ChannelWebhookVerifier> verifiers() {
    return List.of(
        new ViberWebhookVerifier(),
        new WeChatWebhookVerifier(),
        new MetaMessengerWebhookVerifier(),
        new WhatsAppWebhookVerifier(),
        new TelegramWebhookVerifier());
  }

  private static ChannelConnection connection(ChannelProviderType providerType, String mode, String secretRef) {
    ChannelConnection connection = new ChannelConnection(UUID.randomUUID(), providerType, providerType.name(), null, null, secretRef, NOW);
    connection.configureWebhookVerificationMode(mode, NOW);
    return connection;
  }

  private void assertNoSensitiveLeak(String reason) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(reason)
          .as("verifier reason must not leak token '%s'", token)
          .doesNotContain(token);
    }
  }

  @Test
  void signatureHeaderModeFailsClosedEvenWithPresentHostileSignatureHeader() {
    // A hostile, unverified provider signature header is present. Because no real verification exists,
    // every provider verifier must fail closed rather than silently trust the payload.
    Map<String, String> hostileHeaders = Map.of(
        "x-hub-signature-256", "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        "x-viber-content-signature", "forged",
        "x-wechat-signature", "forged",
        "x-telegram-bot-api-secret-token", "forged");

    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "SIGNATURE_HEADER", "vault-ref"), hostileHeaders, "{\"event\":\"message\"}");

      assertThat(result.accepted())
          .as("%s SIGNATURE_HEADER mode must not accept an unverified hostile signature header", verifier.providerType())
          .isFalse();
      assertThat(result.status()).isEqualTo("REJECTED");
      assertThat(result.reason()).doesNotContain("deadbeef");
      assertNoSensitiveLeak(result.reason());
    }
  }

  @Test
  void providerSpecificModeFailsClosedEvenWithPresentHostileSignatureHeader() {
    Map<String, String> hostileHeaders = Map.of("x-hub-signature-256", "sha256=forged");

    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "PROVIDER_SPECIFIC", "vault-ref"), hostileHeaders, "{\"event\":\"message\"}");

      assertThat(result.accepted())
          .as("%s PROVIDER_SPECIFIC mode must not accept an unverified hostile signature header", verifier.providerType())
          .isFalse();
      assertThat(result.status()).isEqualTo("REJECTED");
      assertNoSensitiveLeak(result.reason());
    }
  }

  @Test
  void sharedSecretModeFailsClosedEvenWithPresentHostileSecretHeaderAndConfiguredReference() {
    // A present (but unverified) shared-secret header plus a configured secret reference must NOT be
    // accepted: the raw secret is not compared in this path, so accepting would be a silent fail-open.
    Map<String, String> hostileHeaders = Map.of("x-orderpilot-webhook-secret", "forged-shared-secret");

    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "SHARED_SECRET", "vault-ref"), hostileHeaders, "{\"event\":\"message\"}");

      assertThat(result.accepted())
          .as("%s SHARED_SECRET mode must not accept an unverified secret header", verifier.providerType())
          .isFalse();
      assertThat(result.status()).isEqualTo("REJECTED");
      assertThat(result.reason()).doesNotContain("forged-shared-secret");
      assertNoSensitiveLeak(result.reason());
    }
  }

  @Test
  void missingSignatureHeaderStillFailsClosed() {
    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "SIGNATURE_HEADER", "vault-ref"), Map.of(), "{}");

      assertThat(result.accepted()).isFalse();
      assertThat(result.status()).isEqualTo("REJECTED");
    }
  }

  @Test
  void unsupportedVerificationModeFailsClosed() {
    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "SOME_UNSUPPORTED_MODE", "vault-ref"), Map.of("x-hub-signature-256", "x"), "{}");

      assertThat(result.accepted()).isFalse();
      assertThat(result.status()).isEqualTo("REJECTED");
      assertNoSensitiveLeak(result.reason());
    }
  }

  @Test
  void explicitLocalDevModeRemainsHonestlyAcceptedAsSkipped() {
    // The only honest accept on this path is the explicit local-dev mode, reported as a skip (not a
    // verified accept). This preserves existing local-dev behaviour and is never a production verify.
    for (ChannelWebhookVerifier verifier : verifiers()) {
      VerificationResult result = verifier.verify(
          connection(verifier.providerType(), "DISABLED_FOR_LOCAL_DEV", null), Map.of(), "{}");

      assertThat(result.accepted()).isTrue();
      assertThat(result.status()).isEqualTo("SKIPPED_LOCAL_DEV");
    }
  }
}
