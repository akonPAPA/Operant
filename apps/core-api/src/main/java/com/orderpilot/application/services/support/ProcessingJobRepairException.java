package com.orderpilot.application.services.support;

/**
 * OP-CAP-54 — raised by the processing-job status-repair executor / validator when a repair must fail
 * closed BEFORE any mutation. It carries a stable HTTP error code plus a bounded audit reason code so the
 * controller/handler returns a project-standard safe response and the service can record exactly why the
 * repair was refused — without ever revealing internal/business detail.
 *
 * <ul>
 *   <li>{@link #denied(String, String)} — the approval/target gate failed closed (request not approved,
 *       rejected, expired, or its target type is not {@code PROCESSING_JOB_STATUS_REPAIR}). Mapped to 409.</li>
 *   <li>{@link #validationFailed(String, String)} — the request is approved for the right target, yet the
 *       deterministic validator refused the concrete repair (job missing/cross-tenant, expected-status
 *       mismatch, non-allowlisted transition, not stale, terminal). Nothing is mutated. Mapped to 409.</li>
 * </ul>
 */
public class ProcessingJobRepairException extends RuntimeException {
  public static final String CODE_DENIED = "PROCESSING_JOB_REPAIR_DENIED";
  public static final String CODE_VALIDATION_FAILED = "PROCESSING_JOB_REPAIR_VALIDATION_FAILED";

  private final String code;
  private final String reasonCode;
  private final int httpStatus;

  private ProcessingJobRepairException(String code, String reasonCode, int httpStatus, String message) {
    super(message);
    this.code = code;
    this.reasonCode = reasonCode;
    this.httpStatus = httpStatus;
  }

  /** The approval/target gate is not satisfied — nothing is validated or mutated. */
  public static ProcessingJobRepairException denied(String reasonCode, String message) {
    return new ProcessingJobRepairException(CODE_DENIED, reasonCode, 409, message);
  }

  /** Approved for the right target, but the deterministic validator refused the repair — nothing mutated. */
  public static ProcessingJobRepairException validationFailed(String reasonCode, String message) {
    return new ProcessingJobRepairException(CODE_VALIDATION_FAILED, reasonCode, 409, message);
  }

  public String getCode() {
    return code;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
