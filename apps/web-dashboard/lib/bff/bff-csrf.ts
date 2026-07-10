import { BFF_CSRF_HEADER } from "./bff-config.ts";

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

/** Same-origin check for browser mutations: Origin must match Host (Referer fallback only). */
export function validateSameOrigin(request: Request): boolean {
  const host = request.headers.get("host");
  if (!host) {
    return false;
  }
  const origin = request.headers.get("origin");
  if (origin) {
    return origin === `https://${host}` || origin === `http://${host}`;
  }
  const referer = request.headers.get("referer");
  if (!referer) {
    return false;
  }
  try {
    return new URL(referer).host === host;
  } catch {
    return false;
  }
}
