/**
 * Next.js request-scoped entry: reads `op_session` from the current request Cookie header.
 */
import { headers } from "next/headers.js";
import { BFF_SESSION_COOKIE } from "../bff/bff-config.ts";
import { readSecurityCookieHeader } from "../bff/bff-cookies.ts";

const SESSION_COOKIE_POLICY = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
import { dashboardServerBffFetchWithCookieHeader } from "./dashboard-server-bff-fetch.ts";

if (typeof window !== "undefined") {
  throw new Error("dashboard-server-bff-transport cannot be imported in the browser");
}

export function cookieHeaderForServerBffRequest(rawCookieHeader: string | null | undefined): string | null {
  const sessionId = readSecurityCookieHeader(rawCookieHeader, BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (sessionId === undefined) {
    return null;
  }
  return `${BFF_SESSION_COOKIE}=${encodeURIComponent(sessionId)}`;
}

async function readRequestOpSessionCookieHeader(): Promise<string | null> {
  const requestHeaders = await headers();
  return cookieHeaderForServerBffRequest(requestHeaders.get("cookie"));
}

export async function dashboardServerBffFetch(
  path: string,
  init?: RequestInit
): Promise<Response> {
  const cookieHeader = await readRequestOpSessionCookieHeader();
  return dashboardServerBffFetchWithCookieHeader(cookieHeader, path, init);
}

export { dashboardServerBffFetchWithCookieHeader } from "./dashboard-server-bff-fetch.ts";