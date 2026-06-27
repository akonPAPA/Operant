package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StubWebhookVerificationServiceTest {
  @Test
  void configuredTokenAcceptsMatchingHeader() {
    StubWebhookVerificationService service =
        new StubWebhookVerificationService(false, "email-token", "", environment("prod"));

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
    StubWebhookVerificationService service =
        new StubWebhookVerificationService(false, "email-token", "", environment("prod"));

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
    StubWebhookVerificationService service =
        new StubWebhookVerificationService(false, "", "", environment("prod"));
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
    StubWebhookVerificationService service =
        new StubWebhookVerificationService(true, "", "", environment("test"));
    String payload = "x".repeat((int) IntakeValidationService.DEFAULT_MAX_FILE_BYTES + 1);

    WebhookVerificationService.WebhookVerificationResult result = assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> service.verify("EMAIL", payload, Map.of())
    );

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo("INVALID_INPUT");
  }

  @Test
  void merelyPresentSignatureLikeHeaderIsNeverAcceptedAsVerification() {
    StubWebhookVerificationService service =
        new StubWebhookVerificationService(false, "", "", environment("prod"));

    WebhookVerificationService.WebhookVerificationResult result = service.verify(
        "EMAIL",
        "{}",
        Map.of("X-Fake-Signature", "present-but-not-verified")
    );

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo("VERIFICATION_NOT_CONFIGURED");
  }

  @Test
  void unsignedStubAcceptanceIsRestrictedToExplicitLocalOrTestProfile() {
    StubWebhookVerificationService production =
        new StubWebhookVerificationService(true, "", "", environment("prod"));
    StubWebhookVerificationService test =
        new StubWebhookVerificationService(true, "", "", environment("test"));

    assertThat(production.verify("EMAIL", "{}", Map.of()).accepted()).isFalse();
    assertThat(test.verify("EMAIL", "{}", Map.of()).accepted()).isTrue();
    assertThat(test.verify("EMAIL", "{}", Map.of()).mode()).isEqualTo("STUB_DEV_UNSIGNED");
  }

  private static MockEnvironment environment(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return environment;
  }
}
