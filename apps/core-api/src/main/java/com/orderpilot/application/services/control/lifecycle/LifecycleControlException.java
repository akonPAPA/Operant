package com.orderpilot.application.services.control.lifecycle;

/**
 * P1-E2A - bounded, fail-closed control-slice error signals. Each carries only a stable machine reason
 * code (no raw internal detail, no secret, no path). The controller maps the concrete type to an HTTP
 * status and returns the bounded code; no message from here is ever a raw entity, stack trace, or secret.
 */
public abstract class LifecycleControlException extends RuntimeException {
  private final String reasonCode;

  protected LifecycleControlException(String reasonCode) {
    super(reasonCode);
    this.reasonCode = reasonCode;
  }

  public String reasonCode() {
    return reasonCode;
  }

  /** The lifecycle executor capability is disabled: no operation is created (fail-closed). */
  public static final class ExecutorDisabled extends LifecycleControlException {
    public ExecutorDisabled() {
      super("LIFECYCLE_EXECUTOR_DISABLED");
    }
  }

  /** The request is malformed or violates the bounded contract (e.g. missing/invalid idempotency key). */
  public static final class InvalidRequest extends LifecycleControlException {
    public InvalidRequest(String reasonCode) {
      super(reasonCode);
    }
  }

  /** The idempotency key was reused with a conflicting intent (different requesting principal). */
  public static final class IdempotencyConflict extends LifecycleControlException {
    public IdempotencyConflict() {
      super("IDEMPOTENCY_KEY_CONFLICT");
    }
  }

  /** No operation exists for the given opaque id. */
  public static final class OperationNotFound extends LifecycleControlException {
    public OperationNotFound() {
      super("LIFECYCLE_OPERATION_NOT_FOUND");
    }
  }

  /** The presented fencing token is not the operation's current active token: a stale executor. */
  public static final class StaleFencingToken extends LifecycleControlException {
    public StaleFencingToken() {
      super("STALE_FENCING_TOKEN");
    }
  }

  /** A second, conflicting terminal completion of an already-terminal operation. */
  public static final class CompletionConflict extends LifecycleControlException {
    public CompletionConflict() {
      super("LIFECYCLE_COMPLETION_CONFLICT");
    }
  }
}
