/** Canonical BFF session TTL (seconds). Shared by cookie Max-Age, Redis TTL, and authority validation. */

export const BFF_SESSION_TTL_DEFAULT_SECONDS = 28_800;
export const BFF_SESSION_TTL_MIN_SECONDS = 300;
export const BFF_SESSION_TTL_MAX_SECONDS = 86_400;

const CANONICAL_INT = /^[1-9][0-9]{2,7}$/;

export type ParsedSessionTtl =
  | { ok: true; seconds: number }
  | { ok: false; reason: string };

function parseCanonicalPositiveInt(raw: string): number | null {
  const trimmed = raw.trim();
  if (!CANONICAL_INT.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value)) {
    return null;
  }
  return value;
}

/** Parse env/config TTL. Invalid values are rejected (no silent fallback) when raw is explicit. */
export function parseBffSessionMaxAgeSeconds(
  raw: string | undefined,
  options?: { allowDefaultOnMissing?: boolean }
): ParsedSessionTtl {
  const allowDefault = options?.allowDefaultOnMissing ?? true;
  if (raw === undefined || raw.trim() === "") {
    if (!allowDefault) {
      return { ok: false, reason: "ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS is required" };
    }
    return { ok: true, seconds: BFF_SESSION_TTL_DEFAULT_SECONDS };
  }
  const parsed = parseCanonicalPositiveInt(raw);
  if (parsed === null) {
    return { ok: false, reason: "ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS must be a canonical decimal integer" };
  }
  if (parsed < BFF_SESSION_TTL_MIN_SECONDS) {
    return { ok: false, reason: `ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS must be at least ${BFF_SESSION_TTL_MIN_SECONDS}` };
  }
  if (parsed > BFF_SESSION_TTL_MAX_SECONDS) {
    return { ok: false, reason: `ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS must not exceed ${BFF_SESSION_TTL_MAX_SECONDS}` };
  }
  return { ok: true, seconds: parsed };
}

export function sessionMaxAgeSecondsFromEnv(): number {
  const result = parseBffSessionMaxAgeSeconds(process.env.ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS);
  if (!result.ok) {
    throw new Error(result.reason);
  }
  return result.seconds;
}

/** Reject stored session lifetimes that exceed configured policy (tamper / stale config). */
export function sessionLifetimeWithinPolicy(
  issuedAtEpochSec: number,
  expiresAtEpochSec: number,
  maxAgeSeconds: number,
  nowEpochSec: number
): boolean {
  if (!Number.isFinite(issuedAtEpochSec) || !Number.isFinite(expiresAtEpochSec)) {
    return false;
  }
  const lifetime = expiresAtEpochSec - issuedAtEpochSec;
  if (lifetime <= 0 || lifetime > maxAgeSeconds) {
    return false;
  }
  if (expiresAtEpochSec <= nowEpochSec) {
    return false;
  }
  return true;
}
