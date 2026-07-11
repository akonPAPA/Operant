import {
  hasDemoAuthority,
  missingFrontendAuthorityMessage,
  requireDemoTenantId
} from "./frontend-authority.mjs";
import {
  dashboardCoreApiBaseUrl,
  enrichDashboardRequestInit,
  toProxiedCorePath,
  usesBffTransport
} from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";

export function coreApiBaseUrl() {
  if (usesBffTransport() && typeof window !== "undefined") {
    return "/api/bff";
  }
  return dashboardCoreApiBaseUrl();
}

export { toProxiedCorePath, usesBffTransport };

export function demoScopeHeaders(): Record<string, string> {
  if (usesBffTransport()) {
    return {};
  }
  return { "X-Tenant-Id": requireDemoTenantId() };
}

export function hasDemoScope() {
  return hasDemoAuthority();
}

export function missingDemoScopeMessage(area: string) {
  return missingFrontendAuthorityMessage(area);
}

export type ApiResult<T> =
  | { ok: true; status: 200; data: T }
  | { ok: false; status: 403; kind: "forbidden"; message: string }
  | { ok: false; status: 404; kind: "not_found"; message: string }
  | { ok: false; status: 422; kind: "validation_error"; message: string }
  | { ok: false; status: number; kind: "server_error" | "network_error"; message: string };

export const FORBIDDEN_STATE_MESSAGE = "You do not have access to this workspace or tenant context.";
export const NOT_FOUND_STATE_MESSAGE = "Review not found or no longer available.";
export const VALIDATION_STATE_MESSAGE = "This request could not be processed as submitted.";
export const LOAD_ERROR_STATE_MESSAGE = "Could not load this workspace.";

export function coreApiStatusMessage(status: number): string {
  switch (status) {
    case 403:
      return FORBIDDEN_STATE_MESSAGE;
    case 404:
      return NOT_FOUND_STATE_MESSAGE;
    case 422:
      return VALIDATION_STATE_MESSAGE;
    default:
      return LOAD_ERROR_STATE_MESSAGE;
  }
}

export async function coreApiGet<T>(path: string, init?: RequestInit): Promise<ApiResult<T>> {
  let response: Response;
  try {
    response = await dashboardApiFetch(
      path,
      enrichDashboardRequestInit({
        method: "GET",
        cache: "no-store",
        ...init,
        headers: {
          "Content-Type": "application/json",
          ...demoScopeHeaders(),
          ...((init?.headers as Record<string, string>) ?? {})
        }
      })
    );
  } catch {
    return { ok: false, status: 0, kind: "network_error", message: LOAD_ERROR_STATE_MESSAGE };
  }
  return mapCoreApiResponse<T>(response);
}

async function mapCoreApiResponse<T>(response: Response): Promise<ApiResult<T>> {
  if (response.status === 200) {
    const text = await safeText(response);
    try {
      return { ok: true, status: 200, data: (text ? JSON.parse(text) : null) as T };
    } catch {
      return { ok: false, status: 200, kind: "server_error", message: LOAD_ERROR_STATE_MESSAGE };
    }
  }
  await safeText(response);
  switch (response.status) {
    case 403:
      return { ok: false, status: 403, kind: "forbidden", message: FORBIDDEN_STATE_MESSAGE };
    case 404:
      return { ok: false, status: 404, kind: "not_found", message: NOT_FOUND_STATE_MESSAGE };
    case 422:
      return { ok: false, status: 422, kind: "validation_error", message: VALIDATION_STATE_MESSAGE };
    default:
      return { ok: false, status: response.status, kind: "server_error", message: LOAD_ERROR_STATE_MESSAGE };
  }
}

async function safeText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "";
  }
}
