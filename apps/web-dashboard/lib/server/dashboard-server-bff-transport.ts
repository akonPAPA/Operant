/**
 * Next.js request-scoped entry: reads `op_session` from the current request cookies.
 */
import { cookies } from "next/headers.js";
import { BFF_SESSION_COOKIE } from "../bff/bff-config.ts";
import { dashboardServerBffFetchWithCookieHeader } from "./dashboard-server-bff-fetch.ts";

if (typeof window !== "undefined") {
  throw new Error("dashboard-server-bff-transport cannot be imported in the browser");
}

async function readRequestOpSessionCookieHeader(): Promise<string | null> {
  const jar = await cookies();
  const raw = jar.get(BFF_SESSION_COOKIE)?.value;
  if (raw === undefined) {
    return null;
  }
  return `${BFF_SESSION_COOKIE}=${encodeURIComponent(raw)}`;
}

export async function dashboardServerBffFetch(
  path: string,
  init?: RequestInit
): Promise<Response> {
  const cookieHeader = await readRequestOpSessionCookieHeader();
  return dashboardServerBffFetchWithCookieHeader(cookieHeader, path, init);
}

export { dashboardServerBffFetchWithCookieHeader } from "./dashboard-server-bff-fetch.ts";
