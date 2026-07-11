import {
  enrichDashboardRequestInit,
  toProxiedCorePath,
  usesBffTransport
} from "./api-transport.ts";

const DEFAULT_BASE_URL = "http://localhost:8080";

function isBrowserRuntime(): boolean {
  return typeof window !== "undefined";
}

/**
 * Browser and Client-Component SSR HTTP (same-origin `/api/bff` in production BFF mode).
 * True Server Components use `lib/server/*.server.ts` in-process reads.
 */
export async function dashboardApiFetch(path: string, init?: RequestInit): Promise<Response> {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  if (usesBffTransport()) {
    const url = toProxiedCorePath(normalizedPath);
    return fetch(
      url,
      enrichDashboardRequestInit({
        ...init,
        cache: init?.cache ?? "no-store"
      })
    );
  }
  if (isBrowserRuntime()) {
    const base = (
      process.env.NEXT_PUBLIC_CORE_API_URL ??
      DEFAULT_BASE_URL
    ).replace(/\/$/, "");
    return fetch(
      `${base}${normalizedPath}`,
      enrichDashboardRequestInit({
        ...init,
        cache: init?.cache ?? "no-store"
      })
    );
  }
  const base = (
    process.env.CORE_API_BASE_URL ??
    process.env.NEXT_PUBLIC_CORE_API_URL ??
    DEFAULT_BASE_URL
  ).replace(/\/$/, "");
  return fetch(
    `${base}${normalizedPath}`,
    enrichDashboardRequestInit({
      ...init,
      cache: init?.cache ?? "no-store"
    })
  );
}
