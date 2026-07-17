import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffCookieSecure
} from "./bff-config.ts";

function cookieAttributes(maxAgeSeconds: number): string {
  const secure = bffCookieSecure() ? "; Secure" : "";
  return `Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

export function issueAuthCookies(
  headers: Headers,
  sessionId: string,
  csrf: string,
  maxAgeSeconds: number
): void {
  headers.append("Set-Cookie", `${BFF_SESSION_COOKIE}=${sessionId}; HttpOnly; ${cookieAttributes(maxAgeSeconds)}`);
  headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=${csrf}; ${cookieAttributes(maxAgeSeconds)}`);
}

export function clearAuthCookies(headers: Headers): void {
  headers.append("Set-Cookie", `${BFF_SESSION_COOKIE}=; HttpOnly; ${cookieAttributes(0)}`);
  headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=; ${cookieAttributes(0)}`);
}
