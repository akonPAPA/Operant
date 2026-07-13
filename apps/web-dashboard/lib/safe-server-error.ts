/**
 * F03 — bounded error contract for the server RSC read path (and reused by the browser UI mapper
 * in F07). Framework-free and secret-free so it is testable and safe on any runtime.
 *
 * Raw caught exceptions must never become page-consumed error text: a thrown error can carry a
 * Redis/Core URL, hostname, port, filesystem path, stack frame, SQL fragment, or secret-like token.
 * This module maps failures to a STABLE public code + a bounded, non-technical message + an optional
 * correlation id. The returned message is a constant — never derived from the exception — so no
 * technical detail can leak into the response or the rendered Server Component output. Technical
 * detail goes only to a redacting logger.
 */

export type PublicServerErrorCode =
  | "AUTH_REQUIRED"
  | "ACCESS_DENIED"
  | "NOT_FOUND"
  | "CONFLICT"
  | "VALIDATION_FAILED"
  | "RATE_LIMITED"
  | "TEMPORARILY_UNAVAILABLE"
  | "REQUEST_FAILED";

/** Bounded, non-technical public messages. Never contain infrastructure detail. */
export const PUBLIC_SERVER_ERROR_MESSAGE: Record<PublicServerErrorCode, string> = Object.freeze({
  AUTH_REQUIRED: "You need to sign in to continue.",
  ACCESS_DENIED: "You do not have access to this resource.",
  NOT_FOUND: "The requested resource was not found.",
  CONFLICT: "The request conflicts with the current state.",
  VALIDATION_FAILED: "The request was invalid.",
  RATE_LIMITED: "Too many requests. Please try again shortly.",
  TEMPORARILY_UNAVAILABLE: "This service is temporarily unavailable.",
  REQUEST_FAILED: "The request could not be completed."
});

export type PublicServerError = {
  code: PublicServerErrorCode;
  message: string;
  correlationId: string;
};

/** Map an upstream HTTP status to a stable public code (Core internals are never exposed). */
export function publicCodeForStatus(status: number): PublicServerErrorCode {
  if (status === 401) return "AUTH_REQUIRED";
  if (status === 403) return "ACCESS_DENIED";
  if (status === 404) return "NOT_FOUND";
  if (status === 409) return "CONFLICT";
  if (status === 400 || status === 422) return "VALIDATION_FAILED";
  if (status === 429) return "RATE_LIMITED";
  if (status >= 500) return "TEMPORARILY_UNAVAILABLE";
  return "REQUEST_FAILED";
}

const CORRELATION_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

/** Non-secret request correlation id for support triage. */
export function newCorrelationId(): string {
  let id = "";
  for (let i = 0; i < 12; i += 1) {
    id += CORRELATION_ALPHABET[Math.floor(Math.random() * CORRELATION_ALPHABET.length)];
  }
  return `req_${id}`;
}

/**
 * Redact technical/secret-shaped substrings before anything is logged. Defense in depth for the
 * SERVER LOG only — the returned public error never contains exception-derived text at all.
 */
export function redactTechnicalDetail(input: string): string {
  return (
    input
      // URLs (redis://, postgres://, http(s)://, etc.) including any embedded credentials
      .replace(/\b[a-z][a-z0-9+.-]*:\/\/[^\s'"]+/gi, "[REDACTED_URL]")
      // Windows filesystem paths
      .replace(/\b[A-Za-z]:\\[^\s'"]*/g, "[REDACTED_PATH]")
      // POSIX filesystem/module paths (>=2 segments)
      .replace(/(?:\/[A-Za-z0-9_.@-]+){2,}/g, "[REDACTED_PATH]")
      // IPv4 (+ optional port)
      .replace(/\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b/g, "[REDACTED_IP]")
      // sensitive key=value / header-style pairs
      .replace(
        /\b(authorization|cookie|set-cookie|x-op-csrf-token|x-orderpilot-gateway-signature|idempotency-key|password|secret|token)\b\s*[:=]\s*[^\s'";,]+/gi,
        "$1=[REDACTED]"
      )
      // long hex blobs (hashes, signatures, keys)
      .replace(/\b[A-Fa-f0-9]{16,}\b/g, "[REDACTED_TOKEN]")
      // long base64/base64url blobs
      .replace(/\b[A-Za-z0-9+/_-]{24,}={0,2}\b/g, "[REDACTED_TOKEN]")
      // remaining bare hostnames
      .replace(/\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d+)?\b/gi, "[REDACTED_HOST]")
  );
}

/** Redacted logging. Never logs cookies, signatures, CSRF, authorization, or payloads. */
export function logRedactedServerError(
  correlationId: string,
  code: PublicServerErrorCode,
  error: unknown
): void {
  const name = error instanceof Error ? error.name : typeof error;
  const rawMessage = error instanceof Error ? error.message : String(error);
  const safeMessage = redactTechnicalDetail(rawMessage).slice(0, 300);
  // Only the class name and a redacted, truncated message — no stack, no payload, no headers.
  console.error(`[server-read] correlationId=${correlationId} code=${code} error=${name}: ${safeMessage}`);
}

/**
 * Map a caught exception to a bounded public error. The returned message is a constant keyed only by
 * `code`; the exception text is redacted and sent to the log alone.
 */
export function toPublicServerError(
  error: unknown,
  code: PublicServerErrorCode = "TEMPORARILY_UNAVAILABLE"
): PublicServerError {
  const correlationId = newCorrelationId();
  logRedactedServerError(correlationId, code, error);
  return { code, message: PUBLIC_SERVER_ERROR_MESSAGE[code], correlationId };
}
