/**
 * Executable frontend surface ↔ BFF route contract (server/test owned).
 *
 * This model is NOT exposed to arbitrary browser components for authorization decisions.
 * It proves that each shipped TENANT destination declares:
 * - access plane
 * - capability offer rule
 * - consumed / mutation BFF routes (when any)
 *
 * Authorization remains BFF/Core. UI capability is offer filtering only.
 */

import {
  navigationDestinations,
  UNIVERSAL_TENANT_PATHS,
  type AccessPlane,
  type NavigationAvailability,
  type NavigationDestination
} from "../components/navigation-registry.ts";
import { registeredBffRoutes, type BffHttpMethod, type BffRouteRule } from "./bff/bff-route-registry.ts";
import { PERMISSION_TO_UI_CAPABILITY, type UiCapability } from "./ui-capability-model.ts";

export type SurfaceCapabilityRuleKind = "NONE" | "ANY_OF" | "ALL_OF" | "UNSUPPORTED" | "DEPLOYMENT_GATED";

export type SurfaceCapabilityRule =
  | { kind: "NONE" }
  | { kind: "ANY_OF"; capabilities: readonly UiCapability[] }
  | { kind: "ALL_OF"; capabilities: readonly UiCapability[] }
  | { kind: "UNSUPPORTED" }
  | {
      kind: "DEPLOYMENT_GATED";
      capabilities: readonly UiCapability[];
      gate: "UPLOAD_LOCAL_DEMO";
    };

export type SurfaceBffRouteRef = Readonly<{
  method: BffHttpMethod;
  pattern: string;
}>;

export type FrontendSurfaceContract = Readonly<{
  surfaceId: string;
  canonicalPath: string;
  accessPlane: AccessPlane;
  availability: NavigationAvailability;
  /** Capability required to offer the surface (read path). */
  requiredCapabilityRule: SurfaceCapabilityRule;
  /** When non-null, mutations on this surface require these capabilities (separate from read offer). */
  mutationCapabilityRule: SurfaceCapabilityRule | null;
  consumedBffRoutes: readonly SurfaceBffRouteRef[];
  mutationRoutes: readonly SurfaceBffRouteRef[];
  /** Non-null only when requiredCapabilityRule.kind === "NONE". */
  universalReason: string | null;
}>;

/**
 * Explicit BFF consumption declarations keyed by navigation destination id.
 * Prefer importing route pattern strings that match registeredBffRoutes().
 * Unknown / heuristic filename mapping is forbidden — declare next to the surface.
 */
const CONSUMED_BY_SURFACE: Readonly<Record<string, readonly SurfaceBffRouteRef[]>> = Object.freeze({
  // Universal shell: no protected BFF reads during universal render (analytics panels gated separately).
  "command-center": [],
  analytics: [
    { method: "GET", pattern: "api/v1/analytics/overview" },
    { method: "GET", pattern: "api/stage8/analytics/command-center" },
    { method: "GET", pattern: "api/stage8/value/summary" },
    { method: "GET", pattern: "api/stage8/value/roi-assumptions" }
  ],
  "pilot-readiness": [
    { method: "GET", pattern: "api/v1/pilot/metrics" },
    { method: "GET", pattern: "api/v1/pilot/metrics/exceptions" }
  ],
  "pilot-evidence-report": [{ method: "GET", pattern: "api/v1/pilot/evidence-report" }],
  "pilot-demo-scenarios": [{ method: "GET", pattern: "api/v1/pilot/demo-scenarios" }],
  demo: [{ method: "GET", pattern: "api/v1/commerce-intelligence/demo-flow" }],
  inbox: [
    { method: "GET", pattern: "api/v1/intake/documents" },
    { method: "GET", pattern: "api/v1/intake/messages" },
    { method: "GET", pattern: "api/v1/intake/jobs" }
  ],
  // Local-demo upload is deployment-gated; no production BFF upload route is registered.
  upload: [],
  documents: [{ method: "GET", pattern: "api/v1/intake/documents" }],
  messages: [{ method: "GET", pattern: "api/v1/intake/messages" }],
  extractions: [],
  "processing-jobs": [{ method: "GET", pattern: "api/v1/intake/jobs" }],
  "validation-review": [{ method: "GET", pattern: "api/v1/validation-review" }],
  "exception-cockpit": [{ method: "GET", pattern: "api/v1/validation-review" }],
  "conversion-review": [{ method: "GET", pattern: "api/v1/quote-review/conversion-attempts" }],
  "quote-review": [{ method: "GET", pattern: "api/v1/quote-review/queue" }],
  "review-origin-drafts": [{ method: "GET", pattern: "api/v1/validations/review-drafts" }],
  "rfq-handoffs": [{ method: "GET", pattern: "api/v1/channels/rfq-handoffs" }],
  quotes: [{ method: "GET", pattern: "api/v1/quotes/:quoteId/approval-state" }],
  orders: [{ method: "GET", pattern: "api/v1/workspace/draft-orders/review-queue" }],
  "draft-quote-review": [{ method: "GET", pattern: "api/v1/workspace/draft-quotes/review-queue" }],
  "draft-order-review": [{ method: "GET", pattern: "api/v1/workspace/draft-orders/review-queue" }],
  "order-journey": [
    { method: "GET", pattern: "api/v1/order-journeys" },
    { method: "GET", pattern: "api/v1/order-journeys/attention" }
  ],
  customers: [{ method: "GET", pattern: "api/v1/customers" }],
  products: [{ method: "GET", pattern: "api/v1/products" }],
  inventory: [{ method: "GET", pattern: "api/v1/inventory" }],
  pricing: [],
  imports: [],
  "commerce-intelligence": [{ method: "GET", pattern: "api/v1/commerce-intelligence/demo-flow" }],
  "runtime-control": [{ method: "GET", pattern: "api/v1/runtime-control/demo-flow" }],
  "ai-work": [{ method: "GET", pattern: "api/v1/ai-work/suggestions" }],
  reconciliation: [
    { method: "GET", pattern: "api/stage8/reconciliation/cases" },
    { method: "GET", pattern: "api/stage8/reconciliation/summary" }
  ],
  channels: [{ method: "GET", pattern: "api/v1/channels/bot-bridge/status" }],
  "bot-conversations": [{ method: "GET", pattern: "api/v1/bot-runtime/conversations" }],
  "messenger-bridge": [
    { method: "GET", pattern: "api/v1/channels/bot-bridge/status" },
    { method: "GET", pattern: "api/v1/channels/bot-events" }
  ],
  "channel-identities": [{ method: "GET", pattern: "api/v1/channel-identities" }],
  "inbound-events": [{ method: "GET", pattern: "api/v1/intake/events" }],
  "webhook-events": [{ method: "GET", pattern: "api/v1/channels/bot-events" }],
  "bot-runtime": [{ method: "GET", pattern: "api/v1/bot-runtime/configurations" }],
  "bot-settings": [{ method: "GET", pattern: "api/v1/bot-runtime/settings" }],
  // Mixed ADMIN_SETTINGS_READ + CHANGE_REQUEST_READ — unsupported until split/composite contract.
  integrations: [],
  "sync-events": [{ method: "GET", pattern: "api/stage9/connector-sync-runs" }],
  audit: [],
  settings: [],
  // Non-tenant planes — registered for plane-separation tests; never offered in tenant nav.
  "internal-support": [],
  "internal-support-operations": [],
  "public-order-tracking": []
});

const MUTATIONS_BY_SURFACE: Readonly<Record<string, readonly SurfaceBffRouteRef[]>> = Object.freeze({
  quotes: [
    { method: "POST", pattern: "api/v1/quotes/from-rfq" },
    { method: "POST", pattern: "api/v1/quotes/:quoteId/approve" },
    { method: "POST", pattern: "api/v1/quotes/:quoteId/reject" },
    { method: "POST", pattern: "api/v1/quotes/:quoteId/request-changes" },
    { method: "POST", pattern: "api/v1/quotes/:quoteId/convert-to-internal-order" }
  ]
});

const MUTATION_CAPABILITY_BY_SURFACE: Readonly<
  Record<string, { kind: "ANY_OF"; capabilities: readonly UiCapability[] }>
> = Object.freeze({
  quotes: { kind: "ANY_OF", capabilities: ["QUOTE_ACTION"] }
});

function ruleForDestination(dest: NavigationDestination): SurfaceCapabilityRule {
  if (dest.availability === "UNSUPPORTED") {
    return { kind: "UNSUPPORTED" };
  }
  if (dest.availability === "UPLOAD_CAPABILITY_GATED") {
    if (dest.capability === null) {
      throw new Error(`Upload-gated surface ${dest.id} must declare a UI capability`);
    }
    return {
      kind: "DEPLOYMENT_GATED",
      capabilities: [dest.capability],
      gate: "UPLOAD_LOCAL_DEMO"
    };
  }
  if (dest.capability === null) {
    return { kind: "NONE" };
  }
  return { kind: "ANY_OF", capabilities: [dest.capability] };
}

function universalReasonFor(dest: NavigationDestination): string | null {
  if (dest.capability !== null || dest.availability === "UNSUPPORTED") {
    return null;
  }
  if (dest.path === "/command-center") {
    return "Authenticated landing with static orientation only; analytics panels gated separately by VIEW_ANALYTICS.";
  }
  if (dest.path === "/settings") {
    return "Static settings hub with no protected BFF data fetch in the universal render.";
  }
  return "Universally safe authenticated destination with no protected BFF consumption.";
}

function contractForDestination(dest: NavigationDestination): FrontendSurfaceContract {
  const consumed = CONSUMED_BY_SURFACE[dest.id];
  if (consumed === undefined) {
    throw new Error(`Missing surface BFF consumption declaration for ${dest.id}`);
  }
  const mutations = MUTATIONS_BY_SURFACE[dest.id] ?? [];
  const mutationRule = MUTATION_CAPABILITY_BY_SURFACE[dest.id] ?? null;
  if (mutations.length > 0 && !mutationRule) {
    throw new Error(`Missing mutation capability rule for ${dest.id}`);
  }
  if (mutations.length === 0 && mutationRule) {
    throw new Error(`Mutation capability rule without routes for ${dest.id}`);
  }
  return Object.freeze({
    surfaceId: dest.id,
    canonicalPath: dest.path,
    accessPlane: dest.plane,
    availability: dest.availability,
    requiredCapabilityRule: ruleForDestination(dest),
    mutationCapabilityRule: mutationRule,
    consumedBffRoutes: consumed,
    mutationRoutes: mutations,
    universalReason: universalReasonFor(dest)
  });
}

/** All surface contracts (every access plane registered in navigation). */
export function frontendSurfaceContracts(): readonly FrontendSurfaceContract[] {
  return Object.freeze(navigationDestinations.map(contractForDestination));
}

/** TENANT destinations that may be offered (AVAILABLE or deployment-gated). */
export function shippedTenantSurfaceContracts(): readonly FrontendSurfaceContract[] {
  return Object.freeze(
    frontendSurfaceContracts().filter(
      (surface) => surface.accessPlane === "TENANT" && surface.availability !== "UNSUPPORTED"
    )
  );
}

export function surfaceContractForPath(path: string): FrontendSurfaceContract | null {
  const match = frontendSurfaceContracts().find((surface) => surface.canonicalPath === path);
  return match ?? null;
}

export function findRegisteredBffRoute(ref: SurfaceBffRouteRef): BffRouteRule | null {
  return (
    registeredBffRoutes().find((rule) => rule.method === ref.method && rule.pattern === ref.pattern) ?? null
  );
}

/** Derive UI capability requirements from registered BFF route permissions. */
export function capabilitiesImpliedByRoutes(refs: readonly SurfaceBffRouteRef[]): ReadonlySet<UiCapability> {
  const out = new Set<UiCapability>();
  for (const ref of refs) {
    const rule = findRegisteredBffRoute(ref);
    if (!rule) {
      continue;
    }
    const capability = PERMISSION_TO_UI_CAPABILITY[rule.permission];
    if (capability) {
      out.add(capability);
    }
  }
  return out;
}

export function assertSurfaceContractInvariants(surface: FrontendSurfaceContract): void {
  if (surface.accessPlane !== "TENANT") {
    if (surface.requiredCapabilityRule.kind !== "UNSUPPORTED" && surface.availability !== "UNSUPPORTED") {
      // Non-tenant surfaces must never enter tenant offer paths; they may remain registered.
    }
  }

  if (surface.requiredCapabilityRule.kind === "NONE") {
    if (!UNIVERSAL_TENANT_PATHS.has(surface.canonicalPath) && surface.accessPlane === "TENANT") {
      throw new Error(`${surface.surfaceId}: NONE rule requires UNIVERSAL_TENANT_PATHS membership`);
    }
    if (surface.consumedBffRoutes.length > 0) {
      throw new Error(`${surface.surfaceId}: universal surface must not consume protected BFF routes`);
    }
    if (!surface.universalReason) {
      throw new Error(`${surface.surfaceId}: universal surface requires universalReason`);
    }
  }

  if (surface.requiredCapabilityRule.kind === "UNSUPPORTED") {
    if (surface.availability !== "UNSUPPORTED") {
      throw new Error(`${surface.surfaceId}: UNSUPPORTED rule requires availability UNSUPPORTED`);
    }
  }

  if (surface.mutationRoutes.length > 0) {
    const rule = surface.mutationCapabilityRule;
    if (!rule || rule.kind === "NONE" || rule.kind === "UNSUPPORTED") {
      throw new Error(`${surface.surfaceId}: mutation surface requires mutationCapabilityRule`);
    }
    const readRule = surface.requiredCapabilityRule;
    if (readRule.kind === "NONE" || readRule.kind === "UNSUPPORTED") {
      throw new Error(`${surface.surfaceId}: mutation surface cannot use ${readRule.kind} read rule`);
    }
    const mutationCaps = "capabilities" in rule ? rule.capabilities : [];
    if (mutationCaps.every((cap) => cap.startsWith("VIEW_"))) {
      throw new Error(`${surface.surfaceId}: mutations cannot be gated by VIEW_* only`);
    }
  }

  for (const ref of [...surface.consumedBffRoutes, ...surface.mutationRoutes]) {
    if (!findRegisteredBffRoute(ref)) {
      throw new Error(
        `${surface.surfaceId}: route ${ref.method} ${ref.pattern} is not in registeredBffRoutes()`
      );
    }
  }

  const impliedRead = capabilitiesImpliedByRoutes(surface.consumedBffRoutes);
  const impliedMutation = capabilitiesImpliedByRoutes(surface.mutationRoutes);
  const rule = surface.requiredCapabilityRule;
  if (rule.kind === "ANY_OF" || rule.kind === "ALL_OF" || rule.kind === "DEPLOYMENT_GATED") {
    const allowed = new Set(rule.capabilities);
    for (const capability of impliedRead) {
      if (!allowed.has(capability)) {
        throw new Error(
          `${surface.surfaceId}: consumed route implies ${capability} which is outside read ${rule.kind} ${[...allowed].join(",")}`
        );
      }
    }
    if (rule.kind === "ANY_OF" && impliedRead.size > 1) {
      throw new Error(
        `${surface.surfaceId}: multi-permission read consumption requires ALL_OF, not ANY_OF (${[...impliedRead].join(",")})`
      );
    }
  }

  const mutationRule = surface.mutationCapabilityRule;
  if (mutationRule && (mutationRule.kind === "ANY_OF" || mutationRule.kind === "ALL_OF")) {
    const allowedMut = new Set(mutationRule.capabilities);
    for (const capability of impliedMutation) {
      if (!allowedMut.has(capability)) {
        throw new Error(
          `${surface.surfaceId}: mutation route implies ${capability} outside mutation rule ${[...allowedMut].join(",")}`
        );
      }
    }
  }
}
