import { BFF_SESSION_COOKIE } from "./bff-config.ts";
import { bffInProcessRequest } from "./bff-in-process-request.ts";

/** Testable server read entry: explicit cookie header, no Next request globals. */
export async function bffServerReadWithSessionCookie(
  sessionId: string | null | undefined,
  path: string,
  init?: RequestInit
): Promise<Response> {
  const cookieHeader = sessionId ? `${BFF_SESSION_COOKIE}=${encodeURIComponent(sessionId)}` : null;
  return bffInProcessRequest(cookieHeader, path, {
    ...init,
    method: (init?.method ?? "GET").toUpperCase(),
    cache: "no-store"
  });
}
