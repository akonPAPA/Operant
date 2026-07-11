import { BFF_CSRF_HEADER, bffRuntimeMode } from "./bff/bff-config.ts";
import { readCsrfTokenFromDocumentCookie } from "./dashboard-fetch-headers.ts";

const DEFAULT_BASE_URL = "http://localhost:8080";
const CLIENT_AUTHORITY_HEADERS = new Set(["x-tenant-id", "x-orderpilot-permissions"]);

function isBrowserRuntime(): boolean {
  return typeof window !== "undefined";
}

function headersToRecord(headers?: HeadersInit): Record<string, string> {
  if (!headers) {
    return {};
  }
  if (headers instanceof Headers) {
    return Object.fromEntries(headers.entries());
  }
  if (Array.isArray(headers)) {
    return Object.fromEntries(headers);
  }
  return { ...headers };
}

function stripClientAuthorityHeaders(headers: Record<string, string>): Record<string, string> {
  if (!usesBffTransport()) {
    return headers;
  }
  const sanitized: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!CLIENT_AUTHORITY_HEADERS.has(key.toLowerCase())) {
      sanitized[key] = value;
    }
  }
  return sanitized;
}

/** Explicit static demo bundle (public showcase build) — never a production tenant surface. */
function isStaticDemoBundle(): boolean {
  return process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE === "true";
}

/**
 * Deterministic transport selection. A production browser bundle NEVER consults private
 * server env (ORDERPILOT_BFF_ENABLED is not readable in the browser): any non-demo
 * production client always uses the same-origin /api/bff proxy and never falls back to
 * NEXT_PUBLIC_CORE_API_URL, CORE_API_BASE_URL, or localhost:8080.
 */
export function usesBffTransport(): boolean {
  if (isBrowserRuntime()) {
    return process.env.NODE_ENV === "production" && !isStaticDemoBundle();
  }
  return bffRuntimeMode() === "bff-production";
}

export function dashboardCoreApiBaseUrl(): string {
  if (usesBffTransport()) {
    return "/api/bff";
  }
  // demo/dev only: local development and the explicit static demo build
  return (
    process.env.CORE_API_BASE_URL ??
    process.env.NEXT_PUBLIC_CORE_API_URL ??
    DEFAULT_BASE_URL
  );
}

/** Browser-visible API base: relative BFF in production mode; never exposes Core host. */
export function publicApiBaseUrl(): string {
  return dashboardCoreApiBaseUrl();
}

export function toProxiedCorePath(path: string): string {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  if (usesBffTransport()) {
    const stripped = normalized.startsWith("/api/") ? normalized.slice(1) : normalized.replace(/^\//, "");
    return `/api/bff/${stripped}`;
  }
  return `${publicApiBaseUrl().replace(/\/$/, "")}${normalized}`;
}

/** BFF mode uses server session; demo/dev uses env tenant id. */
export function isDashboardApiAuthorityAvailable(tenantId: string | undefined | null): boolean {
  if (usesBffTransport()) {
    return true;
  }
  return Boolean(tenantId?.trim());
}

export function clientTenantHeaders(
  tenantId: string | undefined,
  permissions?: string
): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (usesBffTransport()) {
    return headers;
  }
  if (tenantId?.trim()) {
    headers["X-Tenant-Id"] = tenantId.trim();
  }
  if (permissions?.trim()) {
    headers["X-OrderPilot-Permissions"] = permissions.trim();
  }
  return headers;
}

export function dashboardRequestHeaders(
  tenantId: string | undefined,
  permissions?: string,
  extra?: HeadersInit
): Record<string, string> {
  return stripClientAuthorityHeaders({
    ...clientTenantHeaders(tenantId, permissions),
    ...headersToRecord(extra)
  });
}

/** Shared mutation helper: attaches the CSRF header for every BFF browser mutation. */
export function enrichDashboardRequestInit(init?: RequestInit): RequestInit {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers = headersToRecord(init?.headers);
  if (
    usesBffTransport()
    && (method === "POST" || method === "PUT" || method === "PATCH" || method === "DELETE")
  ) {
    const csrf = readCsrfTokenFromDocumentCookie();
    if (csrf) {
      headers[BFF_CSRF_HEADER] = csrf;
    }
  }
  return { ...init, headers: stripClientAuthorityHeaders(headers) };
}
