package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.orderpilot.application.services.support.ProcessingJobStatusRepairValidator.RepairPlan;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OP-CAP-54 — deterministic validator unit tests. Proves the allowlist is narrow and fails closed: only a
 * STUCK (stale) PENDING/PROCESSING job may be repaired to FAILED; expected-status mismatch, terminal-success,
 * non-allowlisted target, and non-stale jobs are all refused with a bounded reason code and NO mutation.
 */
class ProcessingJobStatusRepairValidatorTest {
  private static final Instant NOW = Instant.parse("2026-06-26T12:00:00Z");
  private static final Duration THRESHOLD = ProcessingJobStatusRepairValidator.STALENESS_THRESHOLD;

  private final ProcessingJobStatusRepairValidator validator = new ProcessingJobStatusRepairValidator();

  /** Build a ProcessingJob with a controlled status / startedAt / queuedAt (entity setters are package-private). */
  private ProcessingJob job(String status, Instant queuedAt, Instant startedAt) {
    ProcessingJob job = new ProcessingJob(
        UUID.randomUUID(), "EXTRACTION", "DOCUMENT", UUID.randomUUID(), 0, queuedAt);
    ReflectionTestUtils.setField(job, "status", status);
    ReflectionTestUtils.setField(job, "queuedAt", queuedAt);
    ReflectionTestUtils.setField(job, "startedAt", startedAt);
    return job;
  }

  private ProcessingJobRepairException expectFailure(ProcessingJob job, String expected, String desired) {
    return catchThrowableOfType(
        () -> validator.validate(job, expected, desired, NOW), ProcessingJobRepairException.class);
  }

  @Test
  void staleProcessingJobToFailedIsAllowlisted() {
    ProcessingJob job = job("PROCESSING", NOW.minus(Duration.ofHours(2)), NOW.minus(THRESHOLD.plusMinutes(1)));

    RepairPlan plan = validator.validate(job, "PROCESSING", "FAILED", NOW);

    assertThat(plan.previousStatus()).isEqualTo(ProcessingJobStatus.PROCESSING);
    assertThat(plan.targetStatus()).isEqualTo(ProcessingJobStatus.FAILED);
  }

  @Test
  void stalePendingJobWithNoWorkerToFailedIsAllowlisted() {
    ProcessingJob job = job("PENDING", NOW.minus(THRESHOLD.plusMinutes(5)), null);

    RepairPlan plan = validator.validate(job, "PENDING", "FAILED", NOW);

    assertThat(plan.targetStatus()).isEqualTo(ProcessingJobStatus.FAILED);
  }

  @Test
  void desiredStatusOtherThanFailedIsDenied() {
    ProcessingJob job = job("PROCESSING", NOW.minus(Duration.ofHours(2)), NOW.minus(THRESHOLD.plusMinutes(1)));

    ProcessingJobRepairException ex = expectFailure(job, "PROCESSING", "SUCCEEDED");

    assertThat(ex.getReasonCode()).isEqualTo("DESIRED_STATUS_NOT_ALLOWED");
    assertThat(ex.getCode()).isEqualTo(ProcessingJobRepairException.CODE_VALIDATION_FAILED);
  }

  @Test
  void expectedStatusMismatchIsDenied() {
    ProcessingJob job = job("PROCESSING", NOW.minus(Duration.ofHours(2)), NOW.minus(THRESHOLD.plusMinutes(1)));

    ProcessingJobRepairException ex = expectFailure(job, "PENDING", "FAILED");

    assertThat(ex.getReasonCode()).isEqualTo("EXPECTED_STATUS_MISMATCH");
  }

  @Test
  void terminalSuccessJobCannotBeRepaired() {
    ProcessingJob job = job("SUCCEEDED", NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));

    ProcessingJobRepairException ex = expectFailure(job, "SUCCEEDED", "FAILED");

    assertThat(ex.getReasonCode()).isEqualTo("JOB_TERMINAL_SUCCESS");
  }

  @Test
  void nonAllowlistedSourceStatusIsDenied() {
    // FAILED -> FAILED is not an allowlisted unsticking transition.
    ProcessingJob job = job("FAILED", NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));

    ProcessingJobRepairException ex = expectFailure(job, "FAILED", "FAILED");

    assertThat(ex.getReasonCode()).isEqualTo("TRANSITION_NOT_ALLOWLISTED");
  }

  @Test
  void nonStaleProcessingJobIsDenied() {
    // Started only a minute ago — a live worker may still own it, so it is not "stuck".
    ProcessingJob job = job("PROCESSING", NOW.minus(Duration.ofMinutes(2)), NOW.minus(Duration.ofMinutes(1)));

    ProcessingJobRepairException ex = expectFailure(job, "PROCESSING", "FAILED");

    assertThat(ex.getReasonCode()).isEqualTo("JOB_NOT_STALE");
  }

  @Test
  void pendingJobThatHasBeenStartedIsNotTreatedAsNoWorkerStale() {
    // A PENDING row that nonetheless carries startedAt is not "no worker ownership" — deny.
    ProcessingJob job = job("PENDING", NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(30)));

    ProcessingJobRepairException ex = expectFailure(job, "PENDING", "FAILED");

    assertThat(ex.getReasonCode()).isEqualTo("JOB_NOT_STALE");
  }

  @Test
  void blankDesiredStatusIsDenied() {
    ProcessingJob job = job("PROCESSING", NOW.minus(Duration.ofHours(2)), NOW.minus(THRESHOLD.plusMinutes(1)));

    ProcessingJobRepairException ex = expectFailure(job, "PROCESSING", "  ");

    assertThat(ex.getReasonCode()).isEqualTo("DESIRED_STATUS_INVALID");
  }
}
