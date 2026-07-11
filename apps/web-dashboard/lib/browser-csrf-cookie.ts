import { BFF_CSRF_COOKIE } from "./bff/bff-config.ts";

const CSRF_TOKEN = /^[A-Za-z0-9_-]{32,128}$/;

export type CsrfCookieReadResult =
  | { ok: true; token: string }
  | { ok: false; reason: "missing" | "duplicate" | "malformed" | "invalid" };

function decodeCookieValue(raw: string): string | null {
  try {
    return decodeURIComponent(raw);
  } catch {
    return null;
  }
}

/**
 * Canonical browser CSRF cookie reader.
 * Fail-closed on duplicate op_csrf cookies, malformed percent-encoding, empty, or invalid charset/length.
 */
export function readBrowserCsrfCookieFromDocument(cookieHeader: string | undefined | null): CsrfCookieReadResult {
  if (!cookieHeader) {
    return { ok: false, reason: "missing" };
  }
  const matches: string[] = [];
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (!trimmed.startsWith(`${BFF_CSRF_COOKIE}=`)) {
      continue;
    }
    const rawValue = trimmed.slice(BFF_CSRF_COOKIE.length + 1);
    if (rawValue.length === 0) {
      return { ok: false, reason: "malformed" };
    }
    const decoded = decodeCookieValue(rawValue);
    if (decoded === null) {
      return { ok: false, reason: "malformed" };
    }
    matches.push(decoded);
  }
  if (matches.length === 0) {
    return { ok: false, reason: "missing" };
  }
  if (matches.length > 1) {
    return { ok: false, reason: "duplicate" };
  }
  const token = matches[0];
  if (!CSRF_TOKEN.test(token)) {
    return { ok: false, reason: "invalid" };
  }
  return { ok: true, token };
}

export function readBrowserCsrfTokenFromDocument(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const result = readBrowserCsrfCookieFromDocument(document.cookie);
  return result.ok ? result.token : null;
}
