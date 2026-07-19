/**
 * Node-runtime UI capability projection handler.
 *
 * Runs in the API-route module graph (same process/instance family as session bootstrap) so the
 * in-memory session store is not split from authentication. RSC must reach this via trusted
 * ORDERPILOT_PUBLIC_ORIGIN self-fetch when memory sessions are active — never by statically
 * importing loadOperatorSession from a React Server Component layout, and never via Host-derived
 * destinations.
 *
 * SECURITY: returns only allowlisted UI capability identifiers. Never returns tenantId, actorId,
 * raw permissions, session tokens, or staff identity. Offer filtering is not authorization —
 * Core remains authoritative on every subsequent request.
 *
 * Access plane: Tenant User Access only (operator session cookie). External customer, service
 * account, and Operant staff identities are not projected through this tenant shell endpoint.
 */
import { BFF_SESSION_COOKIE } from "../bff/bff-config.ts";
import { readSecurityCookie } from "../bff/bff-cookies.ts";
import { isLocalTestBootstrapAllowed, isProductionNodeRuntime } from "../bff/bff-deployment-profile.ts";
import { loadOperatorSession, SessionStoreUnavailableError } from "../bff/bff-session-store.ts";
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

export async function handleUiCapabilityProjection(request: Request): Promise<Response> {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return jsonProjection(deniedProjection(), 405);
  }

  if (forceUnavailableForLocalTest()) {
    return jsonProjection(unavailableProjection(), 503);
  }

  const cookie = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (cookie.status !== "valid") {
    return jsonProjection(deniedProjection(), 401);
  }

  try {
    const session = await loadOperatorSession(cookie.value);
    if (!session) {
      return jsonProjection(deniedProjection(), 401);
    }
    return jsonProjection(projectionFromPermissions(session.permissions), 200);
  } catch (error) {
    if (error instanceof SessionStoreUnavailableError) {
      return jsonProjection(unavailableProjection(), 503);
    }
    return jsonProjection(unavailableProjection(), 503);
  }
}

function jsonProjection(projection: UiCapabilityProjection, status: number): Response {
  return new Response(JSON.stringify(projection), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": CACHE_CONTROL,
      Vary: "Cookie"
    }
  });
}
