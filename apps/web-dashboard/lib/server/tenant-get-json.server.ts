import "server-only";

import { dashboardServerBffFetchWithCookieHeader } from "./dashboard-server-bff-fetch.ts";

let testServerCookieHeader: string | null | undefined;

export function setTenantServerGetJsonCookieHeaderForTesting(
  cookieHeader: string | null | undefined
): void {
  testServerCookieHeader = cookieHeader;
}

export function resetTenantServerGetJsonCookieHeaderForTesting(): void {
  testServerCookieHeader = undefined;
}

async function serverTenantFetch(path: string, init?: RequestInit): Promise<Response> {
  if (testServerCookieHeader !== undefined) {
    return dashboardServerBffFetchWithCookieHeader(testServerCookieHeader, path, init);
  }
  const { dashboardServerBffFetch } = await import("./dashboard-server-bff-transport.ts");
  return dashboardServerBffFetch(path, init);
}

export type TenantServerJsonResult<T> = {
  data: T;
  error?: string;
};

export type TenantServerNullableJsonResult<T> = {
  data: T | null;
  error?: string;
};

/** Server Component / RSC read path: in-process BFF only (no relative `/api/bff` HTTP). */
export async function tenantServerGetJson<T>(
  path: string
): Promise<TenantServerJsonResult<T>> {
  try {
    const response = await serverTenantFetch(path, { cache: "no-store" });
    if (!response.ok) {
      return { data: [] as T, error: `Core API returned ${response.status}.` };
    }
    return { data: (await response.json()) as T };
  } catch (error) {
    return {
      data: [] as T,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

export async function tenantServerGetJsonNullable<T>(
  path: string
): Promise<TenantServerNullableJsonResult<T>> {
  try {
    const response = await serverTenantFetch(path, { cache: "no-store" });
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : null;
    if (!response.ok) {
      return { data: null, error: `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return {
      data: null,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}
