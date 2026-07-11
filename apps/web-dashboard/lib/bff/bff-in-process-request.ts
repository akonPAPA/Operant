/**
 * In-process BFF read/mutation dispatch for Node server runtime (route handlers + SSR).
 * Accepts an explicit Cookie header value; never reads module-global session state.
 */
import { proxyCoreRequest } from "./bff-proxy.ts";

const INTERNAL_ORIGIN = "http://op-bff.internal";

function splitPathAndQuery(path: string): { pathname: string; search: string } {
  const queryIndex = path.indexOf("?");
  if (queryIndex < 0) {
    return { pathname: path, search: "" };
  }
  return { pathname: path.slice(0, queryIndex), search: path.slice(queryIndex) };
}

export function coreApiPathToBffSegments(pathname: string): string[] {
  const normalized = pathname.startsWith("/") ? pathname : `/${pathname}`;
  return normalized.slice(1).split("/").filter((segment) => segment.length > 0);
}

export async function bffInProcessRequest(
  cookieHeader: string | null | undefined,
  path: string,
  init?: RequestInit
): Promise<Response> {
  const method = (init?.method ?? "GET").toUpperCase();
  const { pathname, search } = splitPathAndQuery(path.startsWith("/") ? path : `/${path}`);
  const segments = coreApiPathToBffSegments(pathname);
  const url = `${INTERNAL_ORIGIN}/api/bff/${segments.join("/")}${search}`;
  const headers = new Headers(init?.headers ?? undefined);
  if (cookieHeader) {
    headers.set("cookie", cookieHeader);
  }
  if (!headers.has("accept")) {
    headers.set("accept", "application/json");
  }
  const request = new Request(url, {
    method,
    headers,
    cache: "no-store",
    body: method === "GET" || method === "HEAD" ? undefined : init?.body
  });
  return proxyCoreRequest(request, segments);
}
