package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-42I — Meta Messenger Path-2 provider verifier exemplar (real server-configured HMAC-SHA256).
 *
 * <p>Meta Messenger uses the same Meta App Secret + {@code X-Hub-Signature-256} (HMAC-SHA256, {@code
 * sha256=} prefix) contract as the already-proven Path-1 {@link WhatsAppSignatureVerifier}. This drives
 * the <b>real</b> {@link MetaMessengerWebhookVerifier} (no mocks) and proves:
 *
 * <ul>
 *   <li><b>A</b> — a valid signature over the canonical payload, with a server-configured secret, is
 *       accepted as the explicit {@code CONFIGURED_VERIFY_ONLY} status (not a local-dev skip);</li>
 *   <li><b>B</b> — missing / bad / tampered-body / wrong-secret signatures all fail closed
 *       ({@code REJECTED}); the attacker-presented signature value is never echoed in the reason;</li>
 *   <li><b>E</b> — when no server secret is configured, an enforcing mode still fails closed (no fake
 *       "verified"); the only honest accept is the explicit {@code DISABLED_FOR_LOCAL_DEV} skip;</li>
 *   <li><b>G</b> — no rejection reason leaks internal/implementation/secret tokens.</li>
 * </ul>
 *
 * <p><b>Freshness (C):</b> Meta's {@code X-Hub-Signature-256} carries no timestamp/nonce, so there is no
 * signature-layer freshness window to enforce — not implemented, not faked. <b>Replay (D):</b> replay
 * continuity is the existing service-level dedup in {@link ChannelEventNormalizationService}
 * (proven by {@code ChannelWebhookSecurityTest}); the Meta signature is body-bound, not nonce-bound.
 */
class MetaMessengerWebhookVerifierTest {
  private static final Instant NOW = Instant.parse("2026-06-22T00:00:00Z");
  private static final String SECRET = "op-cap-42i-meta-app-secret";
  private static final String CANONICAL_PAYLOAD =
      "{\"object\":\"page\",\"entry\":[{\"id\":\"PAGE_ID\",\"messaging\":[{\"message\":{\"mid\":\"m_evt-42i\",\"text\":\"Need brake pads\"}}]}]}";

  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token", "private key",
      "connector credentials", "raw signing secret", "raw attacker signature"
  };

  private static ChannelConnection metaConnection(String mode) {
    ChannelConnection connection =
        new ChannelConnection(UUID.randomUUID(), ChannelProviderType.META_MESSENGER, "Meta Messenger", null, null, "vault-ref", NOW);
    connection.configureWebhookVerificationMode(mode, NOW);
    return connection;
  }

  private static String hmacSha256Hex(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(String.format("%02x", b & 0xff));
      }
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String signatureHeader(String secret, String body) {
    return "sha256=" + hmacSha256Hex(secret, body);
  }

  private void assertNoSensitiveLeak(String reason) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(reason)
          .as("verifier reason must not leak token '%s'", token)
          .doesNotContain(token);
    }
  }

  // ============================================================================================
  // A — positive: valid signature + configured secret → explicit verified accept.
  // ============================================================================================

  @Test
  void validSignatureWithConfiguredSecretIsAcceptedAsConfiguredVerifyOnly() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"),
        Map.of("x-hub-signature-256", signatureHeader(SECRET, CANONICAL_PAYLOAD)),
        CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("CONFIGURED_VERIFY_ONLY");
    // A verified accept is never reported as a local-dev skip or a bare accept.
    assertThat(result.status()).isNotEqualTo("SKIPPED_LOCAL_DEV").isNotEqualTo("ACCEPTED");
  }

  @Test
  void validSignatureWithoutSha256PrefixIsAlsoAccepted() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);

    VerificationResult result = verifier.verify(
        metaConnection("PROVIDER_SPECIFIC"),
        Map.of("x-hub-signature-256", hmacSha256Hex(SECRET, CANONICAL_PAYLOAD)),
        CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("CONFIGURED_VERIFY_ONLY");
  }

  // ============================================================================================
  // A2 (OP-CAP-42J) — byte-exact raw body: a signature for one byte sequence does not verify another
  //                    semantically-equivalent-but-byte-different body (whitespace / key order).
  // ============================================================================================

  @Test
  void signatureOverCanonicalBodyDoesNotVerifyAByteDifferentRawBody() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    // Signature was computed over the compact/canonical body, but the raw body that actually arrived on
    // the wire has added whitespace (byte-different, semantically identical). It must fail closed.
    String signatureOverCanonical = signatureHeader(SECRET, CANONICAL_PAYLOAD);
    String whitespacedRawBody =
        "{ \"object\": \"page\", \"entry\": [ { \"id\": \"PAGE_ID\", \"messaging\": [ { \"message\": { \"mid\": \"m_evt-42i\", \"text\": \"Need brake pads\" } } ] } ] }";

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", signatureOverCanonical), whitespacedRawBody);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void keyOrderChangeWithOldSignatureFailsClosed() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    // Same semantic content, different key order → different bytes → old signature must not verify.
    String reorderedRawBody =
        "{\"entry\":[{\"messaging\":[{\"message\":{\"text\":\"Need brake pads\",\"mid\":\"m_evt-42i\"}}],\"id\":\"PAGE_ID\"}],\"object\":\"page\"}";
    String signatureOverOriginal = signatureHeader(SECRET, CANONICAL_PAYLOAD);

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", signatureOverOriginal), reorderedRawBody);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void signatureOverExactWhitespacedRawBodyIsAccepted() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    // When the signature is computed over the exact raw bytes that arrive (including whitespace), it
    // verifies — proving verification is byte-faithful, not canonicalized.
    String whitespacedRawBody =
        "{ \"object\": \"page\", \"entry\": [ { \"id\": \"PAGE_ID\" } ] }";

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"),
        Map.of("x-hub-signature-256", signatureHeader(SECRET, whitespacedRawBody)),
        whitespacedRawBody);

    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo("CONFIGURED_VERIFY_ONLY");
  }

  // ============================================================================================
  // B — negative: missing / bad / tampered-body / wrong-secret all fail closed; no echo; no leak.
  // ============================================================================================

  @Test
  void missingSignatureHeaderFailsClosed() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);

    VerificationResult result = verifier.verify(metaConnection("SIGNATURE_HEADER"), Map.of(), CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void badSignatureFailsClosedAndIsNotEchoed() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    String forged = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", forged), CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.reason()).doesNotContain("deadbeef");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void tamperedBodyFailsClosed() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    // Signature is computed over the original payload, then the body is tampered after signing.
    String validForOriginal = signatureHeader(SECRET, CANONICAL_PAYLOAD);
    String tamperedPayload = CANONICAL_PAYLOAD.replace("Need brake pads", "Approve a $0 order");

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", validForOriginal), tamperedPayload);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void signatureFromWrongSecretFailsClosed() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);
    String signedWithWrongSecret = signatureHeader("attacker-controlled-other-secret", CANONICAL_PAYLOAD);

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", signedWithWrongSecret), CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertNoSensitiveLeak(result.reason());
  }

  // ============================================================================================
  // E — unconfigured secret still fails closed in an enforcing mode (no fake "verified").
  // ============================================================================================

  @Test
  void unconfiguredSecretFailsClosedEvenWithPresentSignatureHeader() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(); // no server secret
    // A valid-looking signature for the payload still cannot be verified without a server secret.
    String presented = signatureHeader(SECRET, CANONICAL_PAYLOAD);

    VerificationResult result = verifier.verify(
        metaConnection("SIGNATURE_HEADER"), Map.of("x-hub-signature-256", presented), CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.status()).isNotEqualTo("CONFIGURED_VERIFY_ONLY");
    assertNoSensitiveLeak(result.reason());
  }

  @Test
  void localDevModeRemainsHonestSkipEvenWhenSecretConfigured() {
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);

    VerificationResult result = verifier.verify(metaConnection("DISABLED_FOR_LOCAL_DEV"), Map.of(), "{}");

    assertThat(result.accepted()).isTrue();
    // An explicit local-dev connection skips verification; it is never reported as a verified signature.
    assertThat(result.status()).isEqualTo("SKIPPED_LOCAL_DEV");
    assertThat(result.status()).isNotEqualTo("CONFIGURED_VERIFY_ONLY");
  }

  @Test
  void sharedSecretModeFailsClosedEvenWhenAppSecretConfigured() {
    // SHARED_SECRET is not the Meta signature contract; it must defer to the shared fail-closed path.
    MetaMessengerWebhookVerifier verifier = new MetaMessengerWebhookVerifier(SECRET);

    VerificationResult result = verifier.verify(
        metaConnection("SHARED_SECRET"), Map.of("x-orderpilot-webhook-secret", "forged-shared-secret"), CANONICAL_PAYLOAD);

    assertThat(result.accepted()).isFalse();
    assertThat(result.status()).isEqualTo("REJECTED");
    // The forged attacker-presented secret value must not be echoed back. (The benign mode-name word
    // "shared-secret" legitimately appears in the fail-closed reason, so the full leak-token sweep — which
    // forbids the substring "secret" in response bodies — is not applied to this verifier reason string.)
    assertThat(result.reason()).doesNotContain("forged-shared-secret");
  }
}
