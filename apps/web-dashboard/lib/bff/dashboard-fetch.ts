import { bffMutationHeaders } from "./bff-client-headers";
import { dashboardCoreApiBaseUrl, toProxiedCorePath, usesBffTransport } from "../api-transport";

export function dashboardFetchUrl(path: string): string {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (usesBffTransport()) {
    return toProxiedCorePath(normalized);
  }
  return `${dashboardCoreApiBaseUrl().replace(/\/$/, "")}${normalized}`;
}

export function dashboardFetchHeaders(init?: RequestInit): Record<string, string> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {
    "Content-Type": "application/json"
  };
  if (usesBffTransport() && method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    Object.assign(headers, bffMutationHeaders());
  }
  return headers;
}

export async function dashboardFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(dashboardFetchUrl(path), {
    cache: "no-store",
    ...init,
    headers: {
      ...dashboardFetchHeaders(init),
      ...((init?.headers as Record<string, string>) ?? {})
    }
  });
}
