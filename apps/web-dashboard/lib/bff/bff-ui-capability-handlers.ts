/**
 * Node-runtime UI capability projection handler.
 *
 * Runs in the API-route module graph (same process/instance family as session bootstrap) so the
 * in-memory session store is not split from authentication. RSC must reach this via trusted
 * ORDERPILOT_PUBLIC_ORIGIN self-fetch when memory sessions are active — never by statically
 * importing the session store from a React Server Component layout, and never via Host-derived
 * destinations.
 *
 * SECURITY: returns only allowlisted UI capability identifiers. Never returns tenantId, actorId,
 * raw permissions, session tokens, or staff identity. Offer filtering is not authorization —
 * Core remains authoritative on every subsequent request.
 *
 * Access plane: Tenant User Access only (operator session cookie). External customer, service
 * account, and Operant staff identities are not projected through this tenant shell endpoint.
 *
 * Session resolution uses loadOperatorSessionResult so Redis/store outages become 503 UNAVAILABLE
 * rather than 401 DENIED. Ordinary BFF route authorization continues to use fail-closed
 * loadOperatorSession null semantics.
 */
import { BFF_SESSION_COOKIE } from "../bff/bff-config.ts";
import { readSecurityCookie } from "../bff/bff-cookies.ts";
import { isLocalTestBootstrapAllowed, isProductionNodeRuntime } from "../bff/bff-deployment-profile.ts";
import { loadOperatorSessionResult } from "../bff/bff-session-store.ts";
import {
  deniedProjection,
  projectionFromPermissions,
  unavailableProjection,
  type UiCapabilityProjection
} from "../ui-capability-model.ts";

const SESSION_COOKIE_POLICY = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
const CACHE_CONTROL = "private, no-store";

function forceUnavailableForLocalTest(): boolean {
  return (
    !isProductionNodeRuntime() &&
    isLocalTestBootstrapAllowed() &&
    process.env.ORDERPILOT_BFF_FORCE_CAPABILITY_UNAVAILABLE === "true"
  );
}

type ProjectionResolution = {
  projection: UiCapabilityProjection;
  status: number;
};

async function resolveUiCapabilityProjection(request: Request): Promise<ProjectionResolution> {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return { projection: deniedProjection(), status: 405 };
  }

  if (forceUnavailableForLocalTest()) {
    return { projection: unavailableProjection(), status: 503 };
  }

  const cookie = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (cookie.status !== "valid") {
    return { projection: deniedProjection(), status: 401 };
  }

  try {
    const result = await loadOperatorSessionResult(cookie.value);
    if (result.status === "STORE_UNAVAILABLE") {
      return { projection: unavailableProjection(), status: 503 };
    }
    if (result.status === "MISSING_OR_INACTIVE") {
      return { projection: deniedProjection(), status: 401 };
    }
    return { projection: projectionFromPermissions(result.session.permissions), status: 200 };
  } catch {
    // Unknown internal error — UNAVAILABLE without raw detail.
    return { projection: unavailableProjection(), status: 503 };
  }
}

function projectionHeaders(): HeadersInit {
  return {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": CACHE_CONTROL,
    Vary: "Cookie"
  };
}

function createProjectionResponse(
  resolution: ProjectionResolution,
  method: string
): Response {
  const headers = projectionHeaders();
  if (method === "HEAD") {
    return new Response(null, { status: resolution.status, headers });
  }
  return new Response(JSON.stringify(resolution.projection), {
    status: resolution.status,
    headers
  });
}

export async function handleUiCapabilityProjection(request: Request): Promise<Response> {
  const resolution = await resolveUiCapabilityProjection(request);
  return createProjectionResponse(resolution, request.method);
}
