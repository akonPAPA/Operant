import { createHash, timingSafeEqual } from "node:crypto";
import { bffCookieSecure } from "./bff-config.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";

export const OIDC_LOGIN_BINDING_COOKIE = "op_oidc_login";
export const OIDC_LOGIN_BINDING_TTL_SECONDS = 300;

const OIDC_BINDING_COOKIE_POLICY: SecurityCookiePolicy = {
  minLength: 43,
  maxLength: 43,
  pattern: /^[A-Za-z0-9_-]{43}$/
};

function cookieAttributes(maxAgeSeconds: number): string {
  const secure = bffCookieSecure() ? "; Secure" : "";
  return `Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

export function issueOidcBindingCookie(headers: Headers, value: string): void {
  headers.append(
    "Set-Cookie",
    `${OIDC_LOGIN_BINDING_COOKIE}=${value}; HttpOnly; ${cookieAttributes(OIDC_LOGIN_BINDING_TTL_SECONDS)}`
  );
}

export function clearOidcBindingCookie(headers: Headers): void {
  headers.append("Set-Cookie", `${OIDC_LOGIN_BINDING_COOKIE}=; HttpOnly; ${cookieAttributes(0)}`);
}

export function readOidcBindingCookie(request: Request): string | null {
  const result = readSecurityCookie(
    request.headers.get("cookie"),
    OIDC_LOGIN_BINDING_COOKIE,
    OIDC_BINDING_COOKIE_POLICY
  );
  return result.status === "valid" ? result.value : null;
}

export function oidcBindingHash(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("base64url");
}

export function sameOidcBindingHash(left: string, right: string): boolean {
  if (!/^[A-Za-z0-9_-]{43}$/.test(left) || !/^[A-Za-z0-9_-]{43}$/.test(right)) return false;
  return timingSafeEqual(Buffer.from(left, "utf8"), Buffer.from(right, "utf8"));
}
