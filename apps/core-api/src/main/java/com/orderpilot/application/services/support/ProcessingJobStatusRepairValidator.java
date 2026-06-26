package com.orderpilot.application.services.support;

import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-54 — the deterministic validator for the one bounded repair target, {@code
 * PROCESSING_JOB_STATUS_REPAIR}. It decides — with NO side effect and NO mutation — whether a concrete
 * processing-job status repair is allowed. It accepts ONLY a deliberately narrow allowlist of operational
 * transitions and fails closed on everything else, so a wedged job can be unstuck but business truth can
 * never be rewritten.
 *
 * <p>Allowlisted transitions (the only ones this stage permits):
 * <ul>
 *   <li>{@code PROCESSING} stuck beyond the staleness threshold → {@code FAILED};</li>
 *   <li>{@code PENDING} stuck with no worker ownership (never started) beyond the threshold → {@code FAILED}.</li>
 * </ul>
 *
 * <p>Both targets are {@code FAILED} — a terminal state from which the EXISTING control-plane retry path
 * ({@link ProcessingJob#isRetryable()}) can requeue the job. No new status is invented; only the existing
 * {@link ProcessingJobStatus} names are used. A repair to {@code SUCCEEDED} or any other status, a
 * non-stale job, an expected-status mismatch, or a job already in a terminal state is refused.
 */
@Component
public class ProcessingJobStatusRepairValidator {
  /**
   * A job must have been stuck at least this long before a status repair is allowed. This prevents a repair
   * from racing a still-live worker: a freshly-queued or just-claimed job is never "stuck".
   */
  public static final Duration STALENESS_THRESHOLD = Duration.ofMinutes(15);

  /** The single bounded target status this stage may repair a stuck job to. */
  public static final ProcessingJobStatus ALLOWED_TARGET_STATUS = ProcessingJobStatus.FAILED;

  /** A validated, immutable repair plan — the deterministic result handed to the executor. */
  public record RepairPlan(ProcessingJobStatus previousStatus, ProcessingJobStatus targetStatus) {}

  /**
   * Validate a concrete repair. Returns a {@link RepairPlan} when (and only when) the repair is allowlisted;
   * otherwise throws {@link ProcessingJobRepairException#validationFailed} with a bounded reason code and
   * mutates nothing. The caller has already confirmed the request is approved, unexpired, and targets
   * {@code PROCESSING_JOB_STATUS_REPAIR}, and that the job exists and is tenant-scoped.
   */
  public RepairPlan validate(
      ProcessingJob job, String expectedCurrentStatusRaw, String desiredStatusRaw, Instant now) {
    ProcessingJobStatus desired = parseStatus(desiredStatusRaw, "DESIRED_STATUS_INVALID", "desiredStatus");
    if (desired != ALLOWED_TARGET_STATUS) {
      throw ProcessingJobRepairException.validationFailed(
          "DESIRED_STATUS_NOT_ALLOWED",
          "Repair denied: the requested target status is not allowlisted for status repair.");
    }
    ProcessingJobStatus expected =
        parseStatus(expectedCurrentStatusRaw, "EXPECTED_STATUS_INVALID", "expectedCurrentStatus");

    ProcessingJobStatus current = parseStatus(job.getStatus(), "JOB_STATUS_UNREADABLE", "current job status");
    if (current != expected) {
      throw ProcessingJobRepairException.validationFailed(
          "EXPECTED_STATUS_MISMATCH",
          "Repair denied: the job is not in the expected current status.");
    }
    if (current == ProcessingJobStatus.SUCCEEDED) {
      // Defensive: a terminal-success job is never repairable, even if a caller named it as the expected
      // status. Business truth (a completed job) can never be rewound by a status repair.
      throw ProcessingJobRepairException.validationFailed(
          "JOB_TERMINAL_SUCCESS",
          "Repair denied: a successfully-completed job cannot be repaired.");
    }
    if (!isAllowlistedSource(current)) {
      throw ProcessingJobRepairException.validationFailed(
          "TRANSITION_NOT_ALLOWLISTED",
          "Repair denied: this status transition is not allowlisted for status repair.");
    }
    if (!isStale(job, current, now)) {
      throw ProcessingJobRepairException.validationFailed(
          "JOB_NOT_STALE",
          "Repair denied: the job is not stuck beyond the staleness threshold.");
    }
    return new RepairPlan(current, desired);
  }

  private static boolean isAllowlistedSource(ProcessingJobStatus current) {
    return current == ProcessingJobStatus.PENDING || current == ProcessingJobStatus.PROCESSING;
  }

  private boolean isStale(ProcessingJob job, ProcessingJobStatus current, Instant now) {
    Instant cutoff = now.minus(STALENESS_THRESHOLD);
    if (current == ProcessingJobStatus.PROCESSING) {
      // A leased job carries startedAt; it is stuck only if that lease is older than the threshold.
      return job.getStartedAt() != null && job.getStartedAt().isBefore(cutoff);
    }
    // PENDING with no worker ownership: it must never have been started, and have waited past the threshold.
    return job.getStartedAt() == null && job.getQueuedAt() != null && job.getQueuedAt().isBefore(cutoff);
  }

  private static ProcessingJobStatus parseStatus(String raw, String reasonCode, String field) {
    if (raw == null || raw.isBlank()) {
      throw ProcessingJobRepairException.validationFailed(reasonCode, "Repair denied: " + field + " is required.");
    }
    try {
      return ProcessingJobStatus.valueOf(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw ProcessingJobRepairException.validationFailed(
          reasonCode, "Repair denied: " + field + " is not a recognised status.");
    }
  }
}
