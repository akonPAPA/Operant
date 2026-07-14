import "server-only";
import {
  publicCodeForStatus,
  toPublicServerError,
  type PublicServerErrorCode
} from "../safe-server-error.ts";

async function serverTenantFetch(path: string, init?: RequestInit): Promise<Response> {
  const { dashboardServerBffFetch } = await import("./dashboard-server-bff-transport.ts");
  return dashboardServerBffFetch(path, init);
}

export type TenantServerJsonResult<T> = {
  data: T;
  error?: string;
  /** Stable public error code (F03); never derived from raw exception text. */
  code?: PublicServerErrorCode;
  /** Correlation id for support triage; present only on caught-exception failures. */
  correlationId?: string;
};

export type TenantServerNullableJsonResult<T> = {
  data: T | null;
  error?: string;
  code?: PublicServerErrorCode;
  correlationId?: string;
};

/** Server Component / RSC read path: in-process BFF only (no relative `/api/bff` HTTP). */
export async function tenantServerGetJson<T>(
  path: string
): Promise<TenantServerJsonResult<T>> {
  try {
    const response = await serverTenantFetch(path, { cache: "no-store" });
    if (!response.ok) {
      return {
        data: [] as T,
        error: `Core API returned ${response.status}.`,
        code: publicCodeForStatus(response.status)
      };
    }
    return { data: (await response.json()) as T };
  } catch (error) {
    // F03: never surface raw error.message. Bounded message + code; technical detail is logged
    // (redacted) server-side only.
    const publicError = toPublicServerError(error);
    return {
      data: [] as T,
      error: "Core API is not reachable.",
      code: publicError.code,
      correlationId: publicError.correlationId
    };
  }
}

export async function tenantServerGetJsonNullable<T>(
  path: string
): Promise<TenantServerNullableJsonResult<T>> {
  try {
    const response = await serverTenantFetch(path, { cache: "no-store" });
    const text = await response.text();
    if (!response.ok) {
      return {
        data: null,
        error: `Core API returned ${response.status}.`,
        code: publicCodeForStatus(response.status)
      };
    }
    const data = text ? (JSON.parse(text) as T) : null;
    return { data };
  } catch (error) {
    const publicError = toPublicServerError(error);
    return {
      data: null,
      error: "Core API is not reachable.",
      code: publicError.code,
      correlationId: publicError.correlationId
    };
  }
}