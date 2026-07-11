import { enrichDashboardRequestInit, toProxiedCorePath } from "./api-transport.ts";

/** Browser fetch for dashboard API calls; attaches CSRF for BFF mutations. */
export async function dashboardFetch(input: string, init?: RequestInit): Promise<Response> {
  const path = input.startsWith("/") ? input : `/${input}`;
  const url = toProxiedCorePath(path);
  return fetch(
    url,
    enrichDashboardRequestInit({
      ...init,
      cache: init?.cache ?? "no-store"
    })
  );
}
