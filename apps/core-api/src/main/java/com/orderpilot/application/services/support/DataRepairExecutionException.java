package com.orderpilot.application.services.support;

/**
 * OP-CAP-52 — raised by the data-repair execution stub. It carries a stable error code and HTTP status so
 * the controller/handler can return a project-standard safe response that PROVES the future execution gate
 * exists without ever enabling execution:
 *
 * <ul>
 *   <li>{@link #denied(String)} — the approval gate failed closed (no approval / rejected / expired). The
 *       execution attempt is denied; nothing runs and nothing is mutated. Mapped to 409.</li>
 *   <li>{@link #disabled(String)} — the request IS approved and unexpired, yet execution is intentionally
 *       not implemented in this stage. The stub returns {@code DATA_REPAIR_EXECUTION_DISABLED}; still no
 *       business row is read or written. Mapped to 501 (NOT_IMPLEMENTED).</li>
 * </ul>
 *
 * <p>Either way the message is safe and reveals no internal/business detail.
 */
public class DataRepairExecutionException extends RuntimeException {
  public static final String CODE_DENIED = "DATA_REPAIR_EXECUTION_DENIED";
  public static final String CODE_DISABLED = "DATA_REPAIR_EXECUTION_DISABLED";

  private final String code;
  private final int httpStatus;

  private DataRepairExecutionException(String code, int httpStatus, String message) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  /** Execution attempt denied because the approval gate is not satisfied (missing/rejected/expired). */
  public static DataRepairExecutionException denied(String message) {
    return new DataRepairExecutionException(CODE_DENIED, 409, message);
  }

  /** Request is approved, but execution remains disabled in this stage — the stub proves the gate exists. */
  public static DataRepairExecutionException disabled(String message) {
    return new DataRepairExecutionException(CODE_DISABLED, 501, message);
  }

  public String getCode() {
    return code;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
