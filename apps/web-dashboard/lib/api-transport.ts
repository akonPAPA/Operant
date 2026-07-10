import { bffRuntimeMode } from "./bff/bff-config";

const DEFAULT_BASE_URL = "http://localhost:8080";

export function dashboardCoreApiBaseUrl(): string {
  if (usesBffTransport()) {
    return "/api/bff";
  }
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
  if (bffRuntimeMode() === "bff-production") {
    const stripped = normalized.startsWith("/api/") ? normalized.slice(1) : normalized.replace(/^\//, "");
    return `/api/bff/${stripped}`;
  }
  return `${publicApiBaseUrl().replace(/\/$/, "")}${normalized}`;
}

export function usesBffTransport(): boolean {
  return bffRuntimeMode() === "bff-production";
}
