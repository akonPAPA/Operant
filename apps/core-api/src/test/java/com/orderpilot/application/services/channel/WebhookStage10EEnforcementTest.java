package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.security.abuse.AbuseCorpus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-42F — Stage-10E WhatsApp webhook signature / unknown-event / malformed enforcement proof.
 *
 * <p>Drives the <b>real</b> production {@link WhatsAppSignatureVerifier} and {@link WhatsAppInboundAdapter}
 * (no mocks of the guard logic) and proves the Stage-10E webhook security contract for the verifier +
 * normalization boundaries reachable without a Spring context:
 *
 * <ul>
 *   <li>(A) Missing signature, server verification configured → fails closed.
 *   <li>(B) Bad signature, server verification configured → fails closed, no attacker-echo / leak.
 *   <li>(C) Valid HMAC-SHA256 signature → accepted as {@code CONFIGURED_VERIFY_ONLY} (positive proof).
 *   <li>(E) Honest classification: the signature is raw-body-bound, not tenant-bound; tenant isolation
 *       is enforced at the gateway/context layer, not in the signature (no overclaim).
 *   <li>(F) Unknown / unsupported event type → safely ignored (no normalized business message).
 *   <li>(G) Malformed payload → stable redacted error, no internals.
 *   <li>(7) Unconfigured production verification mode fails closed when enforcement is demanded, and
 *       never falsely claims it verified a hostile payload.
 *   <li>(H) Every denied/error status is free of internal/implementation/secret tokens.
 * </ul>
 *
 * <p>Replay/idempotency (D) and cross-tenant message isolation (E, gateway layer) are proven by
 * {@code ChannelGatewayServiceTest} (duplicate external-id dedup; tenant-scoped persistence) and are
 * not duplicated here. Malformed-JSON at the MVC boundary (raw {@code @RequestBody JsonNode} parse
 * failure) is redacted by {@code GlobalExceptionHandler} and proven by {@code ErrorResponseLeakTest}.
 */
class WebhookStage10EEnforcementTest {
  private static final String TEST_APP_SECRET = "op-cap-42f-deterministic-test-secret";
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
  private final ObjectMapper objectMapper = new ObjectMapper();

  // Full leak-token set (OP-CAP-42F). Asserted only against denied/error result statuses.
  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token",
      "private key", "connector credentials", "raw signing secret", "raw system prompt"
  };

  private void assertNoSensitiveLeak(String message) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(message)
          .as("status must not leak sensitive/implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  // ============================================================================================
  // (A) Missing signature — server verification configured.
  // ============================================================================================

  @Test
  void missingSignatureFailsClosedWhenServerVerificationIsConfigured() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);

    var result = verifier.verify(Map.of(), "{\"event\":\"message\"}", ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertNoSensitiveLeak(result.status());
  }

  // ============================================================================================
  // (B) Bad signature — server verification configured.
  // ============================================================================================

  @Test
  void badSignatureFailsClosedAndDoesNotEchoTheAttackerSignatureOrLeak() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);
    String attackerSignature = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    var result = verifier.verify(
        Map.of("X-Hub-Signature-256", attackerSignature),
        "{\"event\":\"message\"}",
        ChannelType.WHATSAPP,
        UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertThat(result.status())
        .as("rejection must not echo the attacker-supplied signature back")
        .doesNotContain(attackerSignature)
        .doesNotContain("deadbeef");
    assertNoSensitiveLeak(result.status());
  }

  // ============================================================================================
  // (C) Valid signature — positive proof.
  // ============================================================================================

  @Test
  void validSignaturePassesVerifierAsConfiguredVerifyOnly() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";
    String signature = "sha256=" + hmacSha256Hex(TEST_APP_SECRET, body);

    var result = verifier.verify(
        Map.of("X-Hub-Signature-256", signature), body, ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.CONFIGURED_VERIFY_ONLY);
    assertThat(result.providerName()).isEqualTo("WHATSAPP");
  }

  @Test
  void validSignatureWithoutSha256PrefixIsAlsoAccepted() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);
    String body = "{\"event\":\"message\",\"id\":\"m-1\"}";
    String signature = hmacSha256Hex(TEST_APP_SECRET, body); // no "sha256=" prefix

    var result = verifier.verify(
        Map.of("X-Hub-Signature-256", signature), body, ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.CONFIGURED_VERIFY_ONLY);
  }

  @Test
  void aValidSignatureForADifferentBodyIsRejected() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);
    String signedBody = "{\"event\":\"message\",\"id\":\"original\"}";
    String tamperedBody = "{\"event\":\"message\",\"id\":\"tampered\"}";
    String signatureForSignedBody = "sha256=" + hmacSha256Hex(TEST_APP_SECRET, signedBody);

    var result = verifier.verify(
        Map.of("X-Hub-Signature-256", signatureForSignedBody),
        tamperedBody,
        ChannelType.WHATSAPP,
        UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertNoSensitiveLeak(result.status());
  }

  // ============================================================================================
  // (Corpus) With production verification configured, EVERY hostile corpus webhook fails closed.
  // ============================================================================================

  @Test
  void everyHostileCorpusWebhookFailsClosedUnderConfiguredVerification() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);

    for (AbuseCorpus.WebhookAbuseSample sample : AbuseCorpus.webhookSamples()) {
      var result = verifier.verify(sample.headers(), sample.rawBody(), ChannelType.WHATSAPP, UUID.randomUUID());

      assertThat(result.accepted())
          .as("hostile webhook %s must fail closed under configured verification", sample.name())
          .isFalse();
      assertThat(result.mode())
          .as("hostile webhook %s must report FAILED, never a verified mode", sample.name())
          .isEqualTo(WebhookVerificationMode.FAILED);
      assertNoSensitiveLeak(result.status());
    }
  }

  @Test
  void everyHostileCorpusWebhookIsNeverFalselyVerifiedWhenUnconfigured() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(); // unconfigured (no secret)

    for (AbuseCorpus.WebhookAbuseSample sample : AbuseCorpus.webhookSamples()) {
      var result = verifier.verify(sample.headers(), sample.rawBody(), ChannelType.WHATSAPP, UUID.randomUUID());

      // Honest unconfigured boundary: it never claims production verification of a hostile payload.
      assertThat(result.mode())
          .as("unconfigured verifier must not falsely verify hostile webhook %s", sample.name())
          .isEqualTo(WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
      assertNoSensitiveLeak(result.status());
    }
  }

  // ============================================================================================
  // (7) Unconfigured production verification mode fails closed when enforcement is demanded.
  // ============================================================================================

  @Test
  void unconfiguredVerifierFailsClosedWhenSignatureEnforcementIsRequired() {
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(); // no server secret
    AbuseCorpus.WebhookAbuseSample probe = AbuseCorpus.webhookRequireSignatureProbe();

    var result = verifier.verify(probe.headers(), probe.rawBody(), ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertNoSensitiveLeak(result.status());
  }

  @Test
  void verificationModeReflectsServerConfigurationNotClientHeaders() {
    assertThat(new WhatsAppSignatureVerifier().verificationMode())
        .isEqualTo(WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
    assertThat(new WhatsAppSignatureVerifier(TEST_APP_SECRET).verificationMode())
        .isEqualTo(WebhookVerificationMode.CONFIGURED_VERIFY_ONLY);
  }

  // ============================================================================================
  // (E) Honest classification — signature is raw-body-bound, not tenant-bound.
  // ============================================================================================

  @Test
  void signatureIsRawBodyBoundNotTenantBound_tenantIsolationEnforcedAtGatewayLayer() {
    // The Stage-10E signature signs the raw body only; it is intentionally NOT tenant-bound. We do not
    // overclaim tenant-bound signatures. Cross-tenant isolation is enforced at the gateway/context layer
    // (TenantContext + tenant-scoped persistence), proven by ChannelGatewayServiceTest.
    WhatsAppSignatureVerifier verifier = new WhatsAppSignatureVerifier(TEST_APP_SECRET);
    String body = "{\"event\":\"message\"}";
    Map<String, String> headers = Map.of("X-Hub-Signature-256", "sha256=" + hmacSha256Hex(TEST_APP_SECRET, body));

    var tenantA = verifier.verify(headers, body, ChannelType.WHATSAPP, UUID.randomUUID());
    var tenantB = verifier.verify(headers, body, ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(tenantA.accepted()).isTrue();
    assertThat(tenantB.accepted()).isTrue();
  }

  // ============================================================================================
  // (F) Unknown / unsupported event type is safely ignored (no normalized business message).
  // ============================================================================================

  @Test
  void unknownOrUnsupportedEventTypeProducesNoNormalizedMessage() throws Exception {
    WhatsAppInboundAdapter adapter = new WhatsAppInboundAdapter(CLOCK);
    // Well-formed Meta envelope carrying an unsupported (non-text) message type.
    JsonNode payload = objectMapper.readTree(
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
        + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.evt\",\"type\":\"reaction\"}]}}]}]}");

    assertThat(adapter.normalize(payload))
        .as("unsupported event type must not yield a normalized business message")
        .isEmpty();
  }

  // ============================================================================================
  // (G) Malformed payload returns a stable redacted error with no internals.
  // ============================================================================================

  @Test
  void malformedWebhookPayloadFailsClosedWithRedactedError() throws Exception {
    WhatsAppInboundAdapter adapter = new WhatsAppInboundAdapter(CLOCK);
    // Structurally-invalid envelope (no entry array) — a valid JSON node but not a valid webhook shape.
    JsonNode payload = objectMapper.readTree("{\"not\":\"a webhook envelope\"}");

    assertThatThrownBy(() -> adapter.normalize(payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed WhatsApp webhook payload")
        .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
  }

  @Test
  void nullWebhookPayloadFailsClosedWithRedactedError() {
    WhatsAppInboundAdapter adapter = new WhatsAppInboundAdapter(CLOCK);

    assertThatThrownBy(() -> adapter.normalize(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed WhatsApp webhook payload")
        .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
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
      throw new IllegalStateException("test unable to compute HMAC");
    }
  }
}
