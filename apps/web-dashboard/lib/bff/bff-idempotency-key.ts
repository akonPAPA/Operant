/**
 * Canonical Idempotency-Key contract for the BFF (F01).
 *
 * Root cause this closes: a present-but-invalid Idempotency-Key must never be silently
 * trimmed, sanitized, or omitted. Silent omission downgrades a keyed mutation into an
 * unkeyed one at Core, defeating replay/dedup protection. The grammar below is the single
 * source of truth (idempotency-key-contract.json) and is byte-identical to the Core Java
 * validator (com.orderpilot.common.api.ClientIdempotencyKey); a parity test on each side
 * asserts the embedded values equal the JSON contract.
 *
 * This module contains no secret material and no Node-only imports, so it is safe on any
 * runtime; validation happens in the proxy BEFORE HMAC signing or any upstream call.
 */

/** Route-level idempotency policy. Replaces the old boolean allowIdempotencyKey. */
export type BffIdempotencyPolicy = "required" | "optional" | "forbidden";

/** Embedded canonical contract (kept in lockstep with idempotency-key-contract.json). */
export const IDEMPOTENCY_KEY_CONTRACT = Object.freeze({
  version: 1,
  header: "Idempotency-Key",
  minLength: 1,
  maxLength: 128,
  /** Canonical grammar; identical to Core ClientIdempotencyKey. No '~', no comma, no whitespace. */
  pattern: "^[A-Za-z0-9._:-]+$"
});

const CANONICAL_KEY = new RegExp(IDEMPOTENCY_KEY_CONTRACT.pattern);

/**
 * Result of resolving the inbound Idempotency-Key header against a route policy.
 * - forward:false  → nothing to forward (absent under optional/forbidden).
 * - forward:true   → forward `value` EXACTLY, unmodified.
 * - ok:false       → the request must be rejected with HTTP 400 (fail closed).
 */
export type IdempotencyResolution =
  | { ok: true; forward: false }
  | { ok: true; forward: true; value: string }
  | { ok: false };

const REJECT: IdempotencyResolution = Object.freeze({ ok: false });

/**
 * Validate a present key value against the canonical contract with NO normalization.
 * A comma is treated as a duplicate/ambiguous marker: the Fetch Headers API joins repeated
 * headers with ", ", and the canonical grammar contains no comma, so any comma is rejected.
 */
export function isCanonicalIdempotencyKey(value: string): boolean {
  if (value.length < IDEMPOTENCY_KEY_CONTRACT.minLength || value.length > IDEMPOTENCY_KEY_CONTRACT.maxLength) {
    return false;
  }
  return CANONICAL_KEY.test(value);
}

/**
 * Resolve the raw header value (as returned by Headers.get, i.e. null when absent, or a
 * comma-joined string when the header was sent more than once) against the route policy.
 *
 * Fail-closed rules (F01):
 *   absent + required   → reject (400)
 *   absent + optional   → accept, forward nothing
 *   absent + forbidden  → accept, forward nothing
 *   present + forbidden → reject (400)
 *   present + valid     → forward the exact value
 *   present + invalid   → reject (400)   (invalid char, whitespace, empty, oversized, comma-joined)
 */
export function resolveIdempotencyKey(
  rawHeaderValue: string | null,
  policy: BffIdempotencyPolicy
): IdempotencyResolution {
  const present = rawHeaderValue !== null;
  if (!present) {
    return policy === "required" ? REJECT : { ok: true, forward: false };
  }
  if (policy === "forbidden") {
    return REJECT;
  }
  // Present under required/optional: validate with no trimming or sanitization.
  if (!isCanonicalIdempotencyKey(rawHeaderValue)) {
    return REJECT;
  }
  return { ok: true, forward: true, value: rawHeaderValue };
}
