/**
 * RSC-safe loader for the UI capability projection.
 *
 * MUST NOT call loadOperatorSession or import the session store module from the static RSC graph
 * when ORDERPILOT_BFF_SESSION_STORE=memory. Those live in a different module instance from the auth
 * route under Next.js memory-session local/dev, which previously caused valid ANALYTICS_READ
 * sessions to project empty and hide legitimate navigation.
 *
 * Destination integrity:
 * - Never builds the fetch URL from Host, X-Forwarded-Host, Forwarded, Origin, or Referer.
 * - Memory local/test: same-origin HTTP to ORDERPILOT_PUBLIC_ORIGIN (validated by bffPublicOrigin).
 * - Redis / production-like: in-process handler call (shared Redis; no parasitic Host hop).
 *
 * Cookie forwarding: only the allowlisted op_session security cookie — never Authorization,
 * gateway signatures, or unrelated cookies.
 */
import "server-only";
import { cache } from "react";
import { headers } from "next/headers.js";
import { isProductionLikeDeployment } from "../bff/bff-deployment-profile.ts";
import {
  parseUiCapabilityProjection,
  unavailableProjection,
  type UiCapabilityProjection
} from "../ui-capability-model.ts";
import {
  sessionCookieHeaderForCapabilityFetch,
  trustedCapabilityFetchOrigin,
  UI_CAPABILITY_RESPONSE_MAX_BYTES
} from "./ui-capability-fetch-policy.server.ts";

function memorySessionStoreActive(): boolean {
  return process.env.ORDERPILOT_BFF_SESSION_STORE === "memory" && !isProductionLikeDeployment();
}

async function parseCapabilityResponse(response: Response): Promise<UiCapabilityProjection> {
  const text = await response.text();
  if (text.length > UI_CAPABILITY_RESPONSE_MAX_BYTES) {
    return unavailableProjection();
  }
  let body: unknown;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    return unavailableProjection();
  }
  const parsed = parseUiCapabilityProjection(body);
  if (!parsed) {
    return unavailableProjection();
  }
  return parsed;
}

async function loadUiCapabilityProjectionUncached(): Promise<UiCapabilityProjection> {
  try {
    const requestHeaders = await headers();
    // Destination integrity: only Cookie is read from the incoming request.
    // Host / X-Forwarded-Host / Forwarded / Origin / Referer are intentionally unread.
    const minimalCookie = sessionCookieHeaderForCapabilityFetch(requestHeaders.get("cookie"));

    if (!memorySessionStoreActive()) {
      // Redis / production-equivalent: in-process handler — shared store, no Host-derived hop.
      const { handleUiCapabilityProjection } = await import("../bff/bff-ui-capability-handlers.ts");
      const request = new Request("http://op-bff.internal/api/ui/capabilities", {
        method: "GET",
        headers: {
          accept: "application/json",
          ...(minimalCookie ? { cookie: minimalCookie } : {})
        },
        cache: "no-store"
      });
      return parseCapabilityResponse(await handleUiCapabilityProjection(request));
    }

    const origin = trustedCapabilityFetchOrigin();
    if (!origin) {
      return unavailableProjection();
    }
    const response = await fetch(`${origin}/api/ui/capabilities`, {
      method: "GET",
      headers: {
        accept: "application/json",
        ...(minimalCookie ? { cookie: minimalCookie } : {})
      },
      cache: "no-store"
    });
    return parseCapabilityResponse(response);
  } catch {
    return unavailableProjection();
  }
}

/** Request-scoped memoization via React cache — never cross-request / cross-user. */
export const loadUiCapabilityProjection = cache(loadUiCapabilityProjectionUncached);
