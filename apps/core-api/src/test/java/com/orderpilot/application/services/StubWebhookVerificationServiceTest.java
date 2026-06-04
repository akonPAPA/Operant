package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubWebhookVerificationServiceTest {
  @Test
  void configuredTokenAcceptsMatchingHeader() {
    StubWebhookVerificationService service = new StubWebhookVerificationService(false, "email-token", "");

    WebhookVerificationService.WebhookVerificationResult result = service.verify(
        "EMAIL",
        "{}",
        Map.of("X-OrderPilot-Webhook-Token", "email-token")
    );

    assertThat(result.accepted()).isTrue();
    assertThat(result.mode()).isEqualTo("TOKEN_MATCH");
  }

  @Test
  void configuredTokenRejectsMismatch() {
    StubWebhookVerificationService service = new StubWebhookVerificationService(false, "email-token", "");

    WebhookVerificationService.WebhookVerificationResult result = service.verify(
        "EMAIL",
        "{}",
        Map.of("X-OrderPilot-Webhook-Token", "wrong-token")
    );

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo("TOKEN_CONFIGURED");
  }

  @Test
  void signatureHeaderDetectionDoesNotUseRegexBacktracking() {
    StubWebhookVerificationService service = new StubWebhookVerificationService(false, "", "");
    String maliciousHeaderName = "a".repeat(200_000) + "signature";

    WebhookVerificationService.WebhookVerificationResult result = assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> service.verify("EMAIL", "{}", Map.of(maliciousHeaderName, "present"))
    );

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo("INVALID_INPUT");
  }

  @Test
  void oversizedPayloadFailsClosedBeforeStubAcceptance() {
    StubWebhookVerificationService service = new StubWebhookVerificationService(true, "", "");
    String payload = "x".repeat((int) IntakeValidationService.DEFAULT_MAX_FILE_BYTES + 1);

    WebhookVerificationService.WebhookVerificationResult result = assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> service.verify("EMAIL", payload, Map.of())
    );

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo("INVALID_INPUT");
  }
}
