import { useCallback, useRef, useState } from "react";

// ── Result type ──────────────────────────────────────────────────────────────

export type OperatorActionResult<T> =
  | { ok: true; data: T; safeMessage: string }
  | { ok: false; errorCode: OperatorActionErrorCode; safeMessage: string };

export type OperatorActionErrorCode =
  | "VALIDATION_FAILED"      // 400, 422
  | "PERMISSION_DENIED"      // 401, 403
  | "NOT_FOUND"              // 404
  | "CONFLICT"               // 409 — already processed / state conflict
  | "RATE_LIMITED"           // 429
  | "ACTION_FAILED";         // 500+ or unknown

// ── Safe error mapper ────────────────────────────────────────────────────────

/**
 * Maps an HTTP error to a safe operator-facing result.
 * Never renders stack traces, raw backend bodies, internal IDs, or full response
 * payloads to the operator.
 */
export function mapOperatorActionError(
  status: number,
  fallbackMessage?: string
): { errorCode: OperatorActionErrorCode; safeMessage: string } {
  if (status === 400 || status === 422) {
    return {
      errorCode: "VALIDATION_FAILED",
      safeMessage: "The request was rejected by backend validation. Check your input and try again."
    };
  }
  if (status === 401 || status === 403) {
    return {
      errorCode: "PERMISSION_DENIED",
      safeMessage: "You do not have permission to perform this action."
    };
  }
  if (status === 404) {
    return {
      errorCode: "NOT_FOUND",
      safeMessage: "The requested record was not found or is no longer available."
    };
  }
  if (status === 409) {
    return {
      errorCode: "CONFLICT",
      safeMessage: "This action conflicts with the current record state. The record may have been updated — please refresh and try again."
    };
  }
  if (status === 429) {
    return {
      errorCode: "RATE_LIMITED",
      safeMessage: "Too many requests. Please wait a moment and try again."
    };
  }
  return {
    errorCode: "ACTION_FAILED",
    safeMessage: fallbackMessage ?? "The action could not be completed. Please try again or contact support."
  };
}

// ── Idempotency key helper ──────────────────────────────────────────────────

/**
 * Builds a *deterministic* idempotency key for a single logical operator action.
 * The key is derived only from the action name and an optional resource handle
 * (e.g. `sourceId`, `quoteId`, or `${sourceId}-${mode}`), so an identical
 * operation always produces an identical key. This is what makes a retry after a
 * lost response (network failure on the way back, accidental re-click) safe: the
 * backend recognizes the matching idempotency key and returns the original result
 * instead of creating a duplicate.
 *
 * The caller is responsible for encoding into `resourceHandle` whatever
 * distinguishes one legitimate operation from another (e.g. dry-run vs draft).
 * Do NOT inject randomness here — a random key would defeat backend dedup.
 *
 * Never includes tenantId, actorId, or other backend-owned authority fields.
 */
export function createOperatorIdempotencyKey(
  actionName: string,
  resourceHandle?: string
): string {
  if (resourceHandle) {
    return `${actionName}-${resourceHandle}`;
  }
  return actionName;
}

// ── Hook ─────────────────────────────────────────────────────────────────────

export type OperatorActionOptions<T> = {
  /** Called when the action succeeds. Receives the parsed data and safe message. */
  onSuccess?: (data: T, safeMessage: string) => void;
  /** Called when the action fails. Receives the error code and safe message. */
  onError?: (errorCode: OperatorActionErrorCode, safeMessage: string) => void;
};

export type OperatorActionState = {
  /** Whether a mutation is currently in flight. */
  pending: boolean;
  /** Whether the action button should be disabled (pending or already succeeded). */
  disabled: boolean;
  /** The last result, if any. */
  result: OperatorActionResult<unknown> | null;
};

/**
 * A reusable hook for operator mutation actions. Provides:
 * - Duplicate-click prevention (ref-based guard)
 * - Pending / disabled state
 * - Idempotency key generation through the shared helper
 * - Safe error mapping (never renders raw backend internals)
 * - Optional onSuccess / onError callbacks
 *
 * Usage:
 *   const { execute, pending, disabled } = useOperatorAction<MyResponse>({
 *     onSuccess: (data) => refresh(),
 *     onError: (code, msg) => setMessage(msg),
 *   });
 *
 *   <button disabled={disabled} onClick={() => execute(async () => {
 *     const res = await apiCall(payload);
 *     return { ok: true, data: res, safeMessage: "Done." };
 *   })}>
 *     {pending ? "Working..." : "Submit"}
 *   </button>
 */
export function useOperatorAction<T>(options?: OperatorActionOptions<T>) {
  const [state, setState] = useState<OperatorActionState>({
    pending: false,
    disabled: false,
    result: null
  });
  const inFlightRef = useRef(false);

  const execute = useCallback(
    async (
      action: () => Promise<OperatorActionResult<T>>
    ): Promise<OperatorActionResult<T>> => {
      // Duplicate-click guard — ref-based to avoid stale closure issues.
      if (inFlightRef.current) {
        return {
          ok: false,
          errorCode: "ACTION_FAILED",
          safeMessage: "Action already in progress."
        };
      }
      inFlightRef.current = true;
      setState({ pending: true, disabled: true, result: null });

      try {
        const result = await action();
        if (result.ok) {
          setState({ pending: false, disabled: false, result });
          options?.onSuccess?.(result.data, result.safeMessage);
        } else {
          setState({ pending: false, disabled: false, result });
          options?.onError?.(result.errorCode, result.safeMessage);
        }
        return result;
      } catch (error) {
        // Unexpected runtime error — map to safe message, never leak raw stack.
        const safeMessage =
          error instanceof Error && error.message
            ? error.message
            : "An unexpected error occurred.";
        const result: OperatorActionResult<T> = {
          ok: false,
          errorCode: "ACTION_FAILED",
          safeMessage
        };
        setState({ pending: false, disabled: false, result });
        options?.onError?.("ACTION_FAILED", safeMessage);
        return result;
      } finally {
        // Release the duplicate-click guard on every path (success, mapped
        // failure, and unexpected exception) so the button is re-enabled for
        // the next legitimate attempt.
        inFlightRef.current = false;
      }
    },
    [options]
  );

  return {
    execute,
    pending: state.pending,
    disabled: state.disabled,
    result: state.result
  };
}
