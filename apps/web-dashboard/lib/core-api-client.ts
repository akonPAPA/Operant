const DEFAULT_BASE_URL = "http://localhost:8080";

export function coreApiBaseUrl() {
  return process.env.NEXT_PUBLIC_CORE_API_URL ?? process.env.CORE_API_BASE_URL ?? DEFAULT_BASE_URL;
}

export function demoScopeHeaders(): Record<string, string> {
  const configuredScope = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "";
  return configuredScope ? { "X-Tenant-Id": configuredScope } : {};
}

export function hasDemoScope() {
  return Boolean(process.env.NEXT_PUBLIC_DEMO_TENANT_ID);
}

export function missingDemoScopeMessage(area: string) {
  return `Configure NEXT_PUBLIC_DEMO_TENANT_ID for ${area}.`;
}

// Typed result for tenant-scoped read calls. 403/404 are valid backend
// security/business outcomes — they must surface as explicit UI states, never
// as unhandled throws or raw Next.js runtime errors.
export type ApiResult<T> =
  | { ok: true; status: 200; data: T }
  | { ok: false; status: 403; kind: "forbidden"; message: string }
  | { ok: false; status: 404; kind: "not_found"; message: string }
  | { ok: false; status: 422; kind: "validation_error"; message: string }
  | { ok: false; status: number; kind: "server_error" | "network_error"; message: string };

// Operator-safe state messages. These intentionally never echo the raw backend
// body, tenant ids, or resource ids: 403/404 must not leak whether a
// cross-tenant resource exists, and 5xx must not expose stack traces.
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

// Tenant-scoped GET helper. Inspects HTTP status before parsing, never assumes a
// JSON body exists for 403/404, and maps network failure into a typed state.
export async function coreApiGet<T>(path: string, init?: RequestInit): Promise<ApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(`${coreApiBaseUrl()}${path}`, {
      method: "GET",
      cache: "no-store",
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...demoScopeHeaders(),
        ...((init?.headers as Record<string, string>) ?? {})
      }
    });
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
  // Drain but never surface the raw body for non-200 states.
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
