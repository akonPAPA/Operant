/**
 * F07 — shared, browser-safe UI error mapper.
 *
 * Browser API helpers must never surface raw exception text (error.message / error.stack /
 * String(error)) or an unvalidated Core response body to the UI. Every failure maps to a STABLE code
 * and a bounded, non-technical message. Reuses the F03 code/message table (single source of truth in
 * safe-server-error.ts) so the server RSC path and the browser client path speak the same vocabulary.
 */
import {
  PUBLIC_SERVER_ERROR_MESSAGE,
  publicCodeForStatus,
  type PublicServerErrorCode
} from "./safe-server-error.ts";

export type UiErrorCode = PublicServerErrorCode;
export const UI_ERROR_MESSAGE = PUBLIC_SERVER_ERROR_MESSAGE;

export type UiError = { code: UiErrorCode; message: string };

/** Map an HTTP status from a non-ok Core/BFF response to a stable UI code + bounded message. */
export function uiErrorForStatus(status: number): UiError {
  const code = publicCodeForStatus(status);
  return { code, message: UI_ERROR_MESSAGE[code] };
}

/**
 * Map a caught exception (network failure, aborted fetch, JSON parse error) to a bounded transport
 * message. The exception text is intentionally ignored — it is never returned to the UI.
 */
export function caughtUiError(_error: unknown): UiError {
  return { code: "TEMPORARILY_UNAVAILABLE", message: "Core API is not reachable." };
}

/** Convenience for helpers whose result shape carries a bounded `error` string. */
export function caughtUiErrorMessage(error: unknown): string {
  return caughtUiError(error).message;
}

/**
 * A deliberately bounded, operator-safe error thrown by an API helper (its message is a curated
 * constant or status-keyed template — never raw backend/exception text). Only these messages may
 * pass through to the UI; any other exception collapses to the caller's bounded fallback.
 */
export class BoundedUiError extends Error {}

/** Surface `error.message` ONLY for BoundedUiError; everything else gets the bounded fallback. */
export function boundedUiErrorMessage(error: unknown, fallback: string): string {
  return error instanceof BoundedUiError ? error.message : fallback;
}
