package com.orderpilot.aibot.domain.aijob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiJobDomainTest {

  @Test
  void leaseAndFailLeaveRecoverableTerminalState() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job =
        new AiJob(
            "aijob_1",
            UUID.randomUUID(),
            AiJobPurpose.BOT_DEFINITION_GENERATION,
            UUID.randomUUID(),
            "idem-1",
            "bot-definition-proposal-v1",
            now);
    job.admit("gemini", "gemini-2.5-flash-lite", now);
    job.lease(now);
    job.markRunning(now);
    job.fail("PROVIDER_TIMEOUT", now);

    assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
    assertThat(job.failureClass()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(job.status().isTerminal()).isTrue();
  }

  @Test
  void illegalShortcutTransitionFails() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job =
        new AiJob(
            "aijob_2",
            UUID.randomUUID(),
            AiJobPurpose.BOT_INTENT_CLASSIFICATION,
            UUID.randomUUID(),
            "idem-2",
            "bot-intent-classification-v1",
            now);
    assertThatThrownBy(() -> job.markRunning(now)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failClaimed_leasedToFailed_clearsLeaseAndSetsFailureClass() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job = newJob("aijob_fc1", now);
    simulateClaim(job, "worker-A", 7L, now);

    job.failClaimed("PROVIDER_DISABLED", now);

    assertThat(job.status()).isEqualTo(AiJobStatus.FAILED);
    assertThat(job.failureClass()).isEqualTo("PROVIDER_DISABLED");
    assertThat(job.completedAt()).isEqualTo(now);
    assertThat(job.leaseOwner()).isNull();
    assertThat(job.leaseUntil()).isNull();
    assertThat(job.nextAttemptAt()).isNull();
    assertThat(job.status().isTerminal()).isTrue();
  }

  @Test
  void failClaimed_requestedState_rejected() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job = newJob("aijob_fc2", now);

    assertThatThrownBy(() -> job.failClaimed("PROVIDER_DISABLED", now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fail_claimed_requires_leased");
  }

  @Test
  void failClaimed_runningState_rejected() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job = newJob("aijob_fc3", now);
    job.admit("gemini", "gemini-2.5-flash-lite", now);
    job.lease(now);
    job.markRunning(now);

    assertThatThrownBy(() -> job.failClaimed("PROVIDER_DISABLED", now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fail_claimed_requires_leased");
  }

  @Test
  void failClaimed_terminalState_rejected() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    AiJob job = newJob("aijob_fc4", now);
    job.admit("gemini", "gemini-2.5-flash-lite", now);
    job.lease(now);
    job.markRunning(now);
    job.fail("PROVIDER_TIMEOUT", now);

    assertThatThrownBy(() -> job.failClaimed("PROVIDER_DISABLED", now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fail_claimed_requires_leased");
  }

  private static AiJob newJob(String publicId, Instant now) {
    return new AiJob(
        publicId,
        UUID.randomUUID(),
        AiJobPurpose.BOT_DEFINITION_GENERATION,
        UUID.randomUUID(),
        "idem-" + publicId,
        "bot-definition-proposal-v1",
        now);
  }

  /** Simulates a persistence-layer atomic claim without requiring full legacy path. */
  private static void simulateClaim(AiJob job, String owner, long fencingToken, Instant now) {
    job.claim(owner, now.plusSeconds(300), fencingToken, 1, now);
  }
}
