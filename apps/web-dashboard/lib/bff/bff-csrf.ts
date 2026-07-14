import { BFF_CSRF_HEADER, bffPublicOrigin } from "./bff-config.ts";

/** Double-submit CSRF: header must exactly match the non-HttpOnly CSRF cookie. */
export function validateCsrf(request: Request, csrfCookie: string | undefined): boolean {
  const header = request.headers.get(BFF_CSRF_HEADER)?.trim();
  if (!header || !csrfCookie) {
    return false;
  }
  if (header.length < 16 || header.length > 256 || !/^[A-Za-z0-9_-]+$/.test(header)) {
    return false;
  }
  return header === csrfCookie;
}

/**
 * Exact public-origin CSRF check for browser mutations.
 * Trusted origin comes only from ORDERPILOT_PUBLIC_ORIGIN — never Host, X-Forwarded-*, or Forwarded.
 */
export function validateSameOrigin(request: Request): boolean {
  const configured = bffPublicOrigin();
  if (!configured) {
    return false;
  }
  const origin = request.headers.get("origin");
  if (origin) {
    // Reject comma-separated / malformed Origin values.
    if (origin.includes(",") || origin !== origin.trim()) {
      return false;
    }
    try {
      const parsed = new URL(origin);
      return parsed.origin === configured && origin === parsed.origin;
    } catch {
      return false;
    }
  }
  const referer = request.headers.get("referer");
  if (!referer) {
    return false;
  }
  try {
    return new URL(referer).origin === configured;
  } catch {
    return false;
  }
}
