/**
 * Trusted destination + cookie projection policy for UI capability self-fetch.
 * No Next request globals — safe to unit-test outside the RSC graph.
 */
import { BFF_SESSION_COOKIE, bffPublicOrigin } from "../bff/bff-config.ts";
import { readSecurityCookieHeader } from "../bff/bff-cookies.ts";

const SESSION_COOKIE_POLICY = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };

/** Hard bound on capability JSON body size (allowlisted identifiers only). */
export const UI_CAPABILITY_RESPONSE_MAX_BYTES = 4_096;

/** Project only the authenticated session cookie into a Cookie header value. */
export function sessionCookieHeaderForCapabilityFetch(
  cookieHeader: string | null | undefined
): string | null {
  const sessionId = readSecurityCookieHeader(cookieHeader, BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (sessionId === undefined) {
    return null;
  }
  return `${BFF_SESSION_COOKIE}=${encodeURIComponent(sessionId)}`;
}

/**
 * Trusted capability endpoint origin. Never derived from request Host / forwarded headers.
 * Returns null when ORDERPILOT_PUBLIC_ORIGIN is missing or invalid → caller fails closed.
 */
export function trustedCapabilityFetchOrigin(): string | null {
  return bffPublicOrigin();
}
