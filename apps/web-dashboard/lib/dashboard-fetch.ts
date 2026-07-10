import { readCsrfTokenFromDocumentCookie } from "./dashboard-fetch-headers.ts";
import { BFF_CSRF_HEADER } from "./bff/bff-config.ts";
import { toProxiedCorePath, usesBffTransport } from "./api-transport.ts";

const MUTATION_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function mutationHeaders(init?: RequestInit): Record<string, string> {
  const method = (init?.method ?? "GET").toUpperCase();
  if (!usesBffTransport() || !MUTATION_METHODS.has(method)) {
    return {};
  }
  const csrf = readCsrfTokenFromDocumentCookie();
  return csrf ? { [BFF_CSRF_HEADER]: csrf } : {};
}

/** Browser fetch for dashboard API calls; attaches CSRF for BFF mutations. */
export async function dashboardFetch(input: string, init?: RequestInit): Promise<Response> {
  const path = input.startsWith("/") ? input : `/${input}`;
  const url = toProxiedCorePath(path);
  const headers = {
    ...(init?.headers as Record<string, string> | undefined),
    ...mutationHeaders(init)
  };
  return fetch(url, {
    ...init,
    headers,
    cache: init?.cache ?? "no-store"
  });
}
