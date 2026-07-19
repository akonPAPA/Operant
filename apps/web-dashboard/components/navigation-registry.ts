/**
 * WP1 — Operant navigation registry (single source of truth for information architecture).
 *
 * This registry describes every canonical product destination with the metadata the rest of
 * the frontend program needs: access plane, required capability, section, legacy aliases,
 * search/palette synonyms, and palette/primary-nav visibility.
 *
 * SECURITY / AUTHORITY NOTE (governing engineering law):
 * - `capability` here is metadata used only to *filter what is offered* in the UI. It is NOT an
 *   authorization decision. UI hiding is never authorization — Core re-checks every request and
 *   remains authoritative. A destination shown here still fails closed at the BFF/Core boundary
 *   if the session lacks the capability.
 * - Only TENANT-plane destinations are ever surfaced in the tenant operator navigation. STAFF,
 *   CUSTOMER, and SERVICE planes are physically separate and are never mixed into tenant nav.
 * - `capability: null` is allowed only for destinations proven universally safe for every
 *   authenticated tenant operator (shell landing / static hubs). Data destinations must declare
 *   an allowlisted UI capability backed by a server-owned permission→capability mapping.
 */

import {
  isUiCapability,
  type UiCapability
} from "../lib/ui-capability-model.ts";

/**
 * Access-plane classification for every destination.
 * Tenant shell may only offer TENANT-plane destinations.
 */
export type AccessPlane =
  | "TENANT"
  | "EXTERNAL_CUSTOMER"
  | "SERVICE"
  | "OPERANT_STAFF"
  | "PUBLIC"
  | "INTERNAL_ONLY";

/** @deprecated Alias retained for older tests/comments — prefer EXTERNAL_CUSTOMER. */
export type LegacyCustomerPlane = "CUSTOMER";
/** @deprecated Alias retained for older tests/comments — prefer OPERANT_STAFF. */
export type LegacyStaffPlane = "STAFF";

export type NavigationAvailability = "AVAILABLE" | "UPLOAD_CAPABILITY_GATED" | "UNSUPPORTED";

/** Allowlisted UI capability required to *offer* this destination (never authorization). */
export type NavigationUiCapability = UiCapability;

export type NavigationDestination = {
  /** Stable identifier (kebab-case), unique across the registry. */
  id: string;
  /** Canonical route path. Exactly one destination per canonical path. */
  path: string;
  /** Display label used in navigation and breadcrumbs. */
  label: string;
  /** Top-level section label (breadcrumb parent / nav group). */
  section: string;
  /** Short section code used as the compact nav glyph. */
  sectionCode: string;
  /** Access plane that owns this destination. */
  plane: AccessPlane;
  /**
   * Required UI capability for offer filtering (metadata only — Core enforces).
   * `null` means any authenticated session of this plane may be *offered* the destination.
   * Only universally safe authenticated destinations may use null.
   */
  capability: NavigationUiCapability | null;
  /** Availability gate. UNSUPPORTED destinations are never offered. */
  availability: NavigationAvailability;
  /** Legacy/duplicate paths that resolve to this canonical destination. */
  aliases: string[];
  /** Extra synonyms for command-palette / search matching. */
  searchAliases: string[];
  /** Whether this destination is offered in the command palette. */
  paletteVisible: boolean;
  /** Whether this destination appears in the tenant primary navigation. */
  showInPrimaryNav: boolean;
};

/**
 * Canonical destinations. Duplicate navigation destinations that previously existed
 * (`Business Value` -> /analytics, `Bot Conversations` -> /bot/conversations, `Audit` -> /audit)
 * are represented as aliases of a single canonical destination, not as separate entries.
 *
 * Classification notes (TENANT plane):
 * - VIEW_ANALYTICS ← ANALYTICS_READ (analytics, pilot, commerce, runtime, reconciliation, journeys)
 * - VIEW_DOCUMENTS ← INTAKE_READ (inbox, documents, messages, inbound events, jobs)
 * - VIEW_REVIEW_QUEUE ← REVIEW_READ (validation/quote/conversion/draft review queues)
 * - VIEW_VALIDATION ← VALIDATION_READ (review-origin validation drafts)
 * - VIEW_CONFIGURATION ← ADMIN_SETTINGS_READ (catalog, channels, identities, sync, messenger)
 * - VIEW_BOT ← BOT_READ (bot runtime / conversations / settings)
 * - VIEW_QUOTES ← QUOTE_READ (quote workspace read surfaces)
 * - VIEW_CHANGE_REQUESTS ← CHANGE_REQUEST_READ (reserved; integrations remains UNSUPPORTED until split)
 * - Universal (capability null): command-center landing (static shell only), settings hub
 * - Upload: VIEW_DOCUMENTS + UPLOAD_CAPABILITY_GATED (local-demo only; never universal)
 */
const destinations: readonly NavigationDestination[] = Object.freeze([
  // --- Command Center (CC) ---
  d({ id: "command-center", path: "/command-center", label: "Command Center", section: "Command Center", sectionCode: "CC" }),
  d({ id: "analytics", path: "/analytics", label: "Analytics", section: "Command Center", sectionCode: "CC", capability: "VIEW_ANALYTICS", searchAliases: ["business value", "roi", "metrics", "kpi"] }),
  d({ id: "pilot-readiness", path: "/pilot-readiness", label: "Pilot Readiness", section: "Command Center", sectionCode: "CC", capability: "VIEW_ANALYTICS" }),
  d({ id: "pilot-evidence-report", path: "/pilot-readiness/evidence-report", label: "Pilot Evidence Report", section: "Command Center", sectionCode: "CC", capability: "VIEW_ANALYTICS" }),
  d({ id: "pilot-demo-scenarios", path: "/pilot-readiness/demo-scenarios", label: "Pilot Demo Scenarios", section: "Command Center", sectionCode: "CC", capability: "VIEW_ANALYTICS" }),
  d({ id: "demo", path: "/demo", label: "Investor Demo", section: "Command Center", sectionCode: "CC", capability: "VIEW_ANALYTICS", searchAliases: ["sandbox", "investor demo"] }),

  // --- Inbox (IN) ---
  d({ id: "inbox", path: "/inbox", label: "Inbox", section: "Inbox", sectionCode: "IN", capability: "VIEW_DOCUMENTS" }),
  d({
    id: "upload",
    path: "/upload",
    label: "Upload",
    section: "Inbox",
    sectionCode: "IN",
    capability: "VIEW_DOCUMENTS",
    availability: "UPLOAD_CAPABILITY_GATED"
  }),
  d({ id: "documents", path: "/documents", label: "Documents", section: "Inbox", sectionCode: "IN", capability: "VIEW_DOCUMENTS" }),
  d({ id: "messages", path: "/messages", label: "Messages", section: "Inbox", sectionCode: "IN", capability: "VIEW_DOCUMENTS" }),
  d({
    id: "extractions",
    path: "/extractions",
    label: "Extractions",
    section: "Inbox",
    sectionCode: "IN",
    capability: "VIEW_DOCUMENTS",
    availability: "UNSUPPORTED",
    paletteVisible: false,
    showInPrimaryNav: false
  }),
  d({ id: "processing-jobs", path: "/processing-jobs", label: "Processing Jobs", section: "Inbox", sectionCode: "IN", capability: "VIEW_DOCUMENTS" }),

  // --- Work Queue (WQ) ---
  d({ id: "validation-review", path: "/validation-review", label: "Validation Review", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "exception-cockpit", path: "/exception-cockpit", label: "Exception Cockpit", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "conversion-review", path: "/conversion-review", label: "Conversion Review", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "quote-review", path: "/quote-review", label: "Quote Review", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "review-origin-drafts", path: "/workspace/review-drafts", label: "Review-Origin Drafts", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_VALIDATION" }),
  d({ id: "rfq-handoffs", path: "/channels/rfq-handoffs", label: "RFQ Handoffs", section: "Work Queue", sectionCode: "WQ", capability: "VIEW_CONFIGURATION" }),

  // --- Transactions (TX) ---
  d({ id: "quotes", path: "/quotes", label: "Draft Quotes", section: "Transactions", sectionCode: "TX", capability: "VIEW_QUOTES" }),
  d({ id: "orders", path: "/orders", label: "Draft Orders", section: "Transactions", sectionCode: "TX", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "draft-quote-review", path: "/workspace/draft-quotes", label: "Draft Quote Review", section: "Transactions", sectionCode: "TX", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "draft-order-review", path: "/workspace/draft-orders", label: "Draft Order Review", section: "Transactions", sectionCode: "TX", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "order-journey", path: "/order-journey", label: "Order Journey", section: "Transactions", sectionCode: "TX", capability: "VIEW_ANALYTICS" }),

  // --- Catalog (CA) ---
  d({ id: "customers", path: "/customers", label: "Customers", section: "Catalog", sectionCode: "CA", capability: "VIEW_CONFIGURATION" }),
  d({ id: "products", path: "/products", label: "Products", section: "Catalog", sectionCode: "CA", capability: "VIEW_CONFIGURATION" }),
  d({ id: "inventory", path: "/inventory", label: "Inventory", section: "Catalog", sectionCode: "CA", capability: "VIEW_CONFIGURATION" }),
  d({
    id: "pricing",
    path: "/pricing",
    label: "Pricing",
    section: "Catalog",
    sectionCode: "CA",
    capability: "VIEW_CONFIGURATION",
    availability: "UNSUPPORTED",
    paletteVisible: false,
    showInPrimaryNav: false
  }),
  d({
    id: "imports",
    path: "/imports",
    label: "Imports",
    section: "Catalog",
    sectionCode: "CA",
    capability: "VIEW_CONFIGURATION",
    availability: "UNSUPPORTED",
    paletteVisible: false,
    showInPrimaryNav: false
  }),

  // --- Intelligence (AI) ---
  d({ id: "commerce-intelligence", path: "/commerce-intelligence", label: "Commerce Intelligence", section: "Intelligence", sectionCode: "AI", capability: "VIEW_ANALYTICS" }),
  d({ id: "runtime-control", path: "/runtime-control", label: "Runtime Control Telemetry", section: "Intelligence", sectionCode: "AI", capability: "VIEW_ANALYTICS" }),
  d({ id: "ai-work", path: "/ai-work", label: "AI Work Assistant", section: "Intelligence", sectionCode: "AI", capability: "VIEW_REVIEW_QUEUE" }),
  d({ id: "reconciliation", path: "/reconciliation", label: "Reconciliation", section: "Intelligence", sectionCode: "AI", capability: "VIEW_ANALYTICS" }),

  // --- Channels (CH) --- (`/bot/conversations` is a legacy alias of `/bot-conversations`)
  d({ id: "channels", path: "/channels", label: "Channels", section: "Channels", sectionCode: "CH", capability: "VIEW_CONFIGURATION" }),
  d({ id: "bot-conversations", path: "/bot-conversations", label: "Bot / Conversations", section: "Channels", sectionCode: "CH", capability: "VIEW_BOT", aliases: ["/bot/conversations"] }),
  d({ id: "messenger-bridge", path: "/messenger-bridge", label: "Messenger Bridge", section: "Channels", sectionCode: "CH", capability: "VIEW_CONFIGURATION" }),
  d({ id: "channel-identities", path: "/channel-identities", label: "Channel Identities", section: "Channels", sectionCode: "CH", capability: "VIEW_CONFIGURATION" }),
  d({ id: "inbound-events", path: "/inbound-events", label: "Inbound Events", section: "Channels", sectionCode: "CH", capability: "VIEW_DOCUMENTS" }),
  d({ id: "webhook-events", path: "/webhook-events", label: "Webhook Events", section: "Channels", sectionCode: "CH", capability: "VIEW_CONFIGURATION" }),
  d({ id: "bot-runtime", path: "/bot-runtime", label: "Bot Runtime", section: "Channels", sectionCode: "CH", capability: "VIEW_BOT" }),
  d({ id: "bot-settings", path: "/bot-settings", label: "Bot Settings", section: "Channels", sectionCode: "CH", capability: "VIEW_BOT" }),

  // --- Control Center (CT) --- (`/audit` is a legacy alias of the canonical `/audit-log`)
  // Integrations previously mixed ADMIN_SETTINGS_READ + CHANGE_REQUEST_READ and converted denied
  // change-request reads into empty lists. Unsupported in primary offer until split/composite.
  d({
    id: "integrations",
    path: "/integrations",
    label: "Integrations",
    section: "Control Center",
    sectionCode: "CT",
    capability: "VIEW_CONFIGURATION",
    availability: "UNSUPPORTED",
    paletteVisible: false,
    showInPrimaryNav: false
  }),
  d({ id: "sync-events", path: "/sync-events", label: "Sync Events", section: "Control Center", sectionCode: "CT", capability: "VIEW_CONFIGURATION" }),
  d({
    id: "audit",
    path: "/audit-log",
    label: "Audit / Security",
    section: "Control Center",
    sectionCode: "CT",
    aliases: ["/audit"],
    capability: "VIEW_CONFIGURATION",
    availability: "UNSUPPORTED",
    paletteVisible: false,
    showInPrimaryNav: false
  }),

  // --- Settings (ST) — static hub; no BFF data call; universally safe for authenticated operators ---
  d({ id: "settings", path: "/settings", label: "Settings", section: "Settings", sectionCode: "ST" }),

  // --- OPERANT_STAFF plane (Operant Support & Maintenance) — never surfaced in tenant nav or palette ---
  d({ id: "internal-support", path: "/internal-support", label: "Internal Support", section: "Operant Support", sectionCode: "SP", plane: "OPERANT_STAFF", paletteVisible: false, showInPrimaryNav: false }),
  d({ id: "internal-support-operations", path: "/internal-support/operations", label: "Support Operations", section: "Operant Support", sectionCode: "SP", plane: "OPERANT_STAFF", paletteVisible: false, showInPrimaryNav: false }),

  // --- EXTERNAL_CUSTOMER plane (buyer-safe) — never surfaced in tenant nav or palette ---
  d({ id: "public-order-tracking", path: "/public/order-tracking", label: "Order Tracking", section: "Customer Portal", sectionCode: "PT", plane: "EXTERNAL_CUSTOMER", paletteVisible: false, showInPrimaryNav: false })
]);

/**
 * Builds a destination with conservative defaults so each call site only states what differs.
 * Defaults: TENANT plane, no required capability, always available, no aliases, palette + nav visible.
 */
function d(input: {
  id: string;
  path: string;
  label: string;
  section: string;
  sectionCode: string;
  plane?: AccessPlane;
  capability?: NavigationUiCapability | null;
  availability?: NavigationAvailability;
  aliases?: string[];
  searchAliases?: string[];
  paletteVisible?: boolean;
  showInPrimaryNav?: boolean;
}): NavigationDestination {
  const capability = input.capability ?? null;
  if (capability !== null && !isUiCapability(capability)) {
    throw new Error(`Unknown navigation UI capability for ${input.id}: ${capability}`);
  }
  return {
    id: input.id,
    path: input.path,
    label: input.label,
    section: input.section,
    sectionCode: input.sectionCode,
    plane: input.plane ?? "TENANT",
    capability,
    availability: input.availability ?? "AVAILABLE",
    aliases: input.aliases ?? [],
    searchAliases: input.searchAliases ?? [],
    paletteVisible: input.paletteVisible ?? true,
    showInPrimaryNav: input.showInPrimaryNav ?? true
  };
}

/** All registered destinations across every access plane. */
export const navigationDestinations: readonly NavigationDestination[] = destinations;

/**
 * Proven universally safe TENANT destinations (capability null allowed).
 * All other TENANT destinations must declare an allowlisted UI capability.
 */
export const UNIVERSAL_TENANT_PATHS: ReadonlySet<string> = new Set([
  "/command-center",
  "/settings"
]);

/** Legacy alias path -> canonical path (used for safe redirects and palette resolution). */
export const legacyAliasMap: Readonly<Record<string, string>> = Object.freeze(
  destinations.reduce<Record<string, string>>((acc, dest) => {
    for (const alias of dest.aliases) {
      acc[alias] = dest.path;
    }
    return acc;
  }, {})
);

/**
 * Resolves a path to its canonical destination path. Returns the canonical path for a known
 * alias, the same path if it is already canonical, or `null` if the path is unknown.
 */
export function resolveCanonicalPath(path: string): string | null {
  if (legacyAliasMap[path]) {
    return legacyAliasMap[path];
  }
  return destinations.some((dest) => dest.path === path) ? path : null;
}

/** Returns the destination for a canonical or alias path, or `null` when unknown. */
export function destinationForPath(path: string): NavigationDestination | null {
  const canonical = resolveCanonicalPath(path);
  if (!canonical) {
    return null;
  }
  return destinations.find((dest) => dest.path === canonical) ?? null;
}

function isOfferable(dest: NavigationDestination, capabilities?: ReadonlySet<string>): boolean {
  if (dest.availability === "UNSUPPORTED") {
    return false;
  }
  if (capabilities && dest.capability !== null) {
    return capabilities.has(dest.capability);
  }
  return true;
}

/**
 * Tenant primary navigation destinations. When `capabilities` is provided, destinations whose
 * required UI capability is absent are omitted from what is *offered* (never a security boundary —
 * Core still authorizes). When omitted, all tenant primary destinations are returned.
 * Unknown required capabilities are fail-closed (not offered). Unsupported destinations are never offered.
 */
export function tenantPrimaryDestinations(
  capabilities?: ReadonlySet<string>
): NavigationDestination[] {
  return destinations.filter((dest) => {
    if (dest.plane !== "TENANT" || !dest.showInPrimaryNav) {
      return false;
    }
    return isOfferable(dest, capabilities);
  });
}

/** Palette-visible destinations for a plane (defaults to TENANT), optionally capability-filtered. */
export function paletteDestinations(
  plane: AccessPlane = "TENANT",
  capabilities?: ReadonlySet<string>
): NavigationDestination[] {
  return destinations.filter((dest) => {
    if (dest.plane !== plane || !dest.paletteVisible) {
      return false;
    }
    return isOfferable(dest, capabilities);
  });
}

/**
 * Build section-grouped navigation from the registry (single source of truth).
 * Optional capability filter applies offer filtering only.
 */
export function tenantNavigationGroupsFromRegistry(
  capabilities?: ReadonlySet<string>
): Array<{
  label: string;
  href: string;
  code: string;
  items: Array<{ label: string; href: string }>;
}> {
  const offered = tenantPrimaryDestinations(capabilities);
  const groups: Array<{
    label: string;
    href: string;
    code: string;
    items: Array<{ label: string; href: string }>;
  }> = [];
  const indexBySection = new Map<string, number>();
  for (const dest of offered) {
    let index = indexBySection.get(dest.section);
    if (index === undefined) {
      index = groups.length;
      indexBySection.set(dest.section, index);
      groups.push({
        label: dest.section,
        href: dest.path,
        code: dest.sectionCode,
        items: []
      });
    }
    groups[index].items.push({ label: dest.label, href: dest.path });
  }
  return groups.filter((group) => group.items.length > 0);
}

export type Breadcrumb = { label: string; path?: string };

/** Breadcrumb trail (section -> destination) for a canonical or alias path, or `null` if unknown. */
export function breadcrumbTrailForPath(path: string): Breadcrumb[] | null {
  const dest = destinationForPath(path);
  if (!dest) {
    return null;
  }
  return [{ label: dest.section }, { label: dest.label, path: dest.path }];
}

/** Breadcrumb trail resolved from a page title (matches a destination label), or `null`. */
export function breadcrumbTrailForTitle(title: string): Breadcrumb[] | null {
  const dest = destinations.find((entry) => entry.label === title);
  if (!dest) {
    return null;
  }
  return [{ label: dest.section }, { label: dest.label, path: dest.path }];
}
