/**
 * Server-owned safe workspace destination policy after authentication.
 *
 * Uses allowlisted UI capability projection only — never client-supplied role,
 * browser storage, query parameters, or raw backend permission strings from the browser.
 */
import "server-only";
import { tenantPrimaryDestinations } from "../../components/navigation-registry.ts";
import { loadUiCapabilityProjection } from "./load-ui-capability-projection.server.ts";
import { offerFilterCapabilities } from "../ui-capability-model.ts";

/** Prefer real work surfaces over the universal landing when offered. */
const PREFERRED_WORK_PATHS: readonly string[] = Object.freeze([
  "/documents",
  "/inbound-events",
  "/quote-review",
  "/validation-review",
  "/inbox"
]);

const UNIVERSAL_LANDING = "/command-center";

/**
 * Resolve the first safe post-auth destination for the current request session.
 * Session denied → caller should send the user through auth/access-denied flows.
 * Projection unavailable → degraded universal landing.
 * No offered business surface → universal landing.
 */
export async function resolveSafeWorkspacePath(): Promise<{
  path: string;
  reason: "WORK_SURFACE" | "UNIVERSAL_LANDING" | "DEGRADED_UNIVERSAL" | "SESSION_DENIED";
}> {
  const projection = await loadUiCapabilityProjection();
  if (projection.status === "DENIED") {
    return { path: "/login", reason: "SESSION_DENIED" };
  }
  if (projection.status === "UNAVAILABLE") {
    return { path: UNIVERSAL_LANDING, reason: "DEGRADED_UNIVERSAL" };
  }

  const offered = tenantPrimaryDestinations(offerFilterCapabilities(projection));
  const offeredPaths = new Set(offered.map((dest) => dest.path));
  for (const preferred of PREFERRED_WORK_PATHS) {
    if (offeredPaths.has(preferred)) {
      return { path: preferred, reason: "WORK_SURFACE" };
    }
  }
  return { path: UNIVERSAL_LANDING, reason: "UNIVERSAL_LANDING" };
}
