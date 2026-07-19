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
 */

export type AccessPlane = "TENANT" | "CUSTOMER" | "SERVICE" | "STAFF";

export type NavigationAvailability = "AVAILABLE" | "UPLOAD_CAPABILITY_GATED";

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
   * Required backend capability (metadata only — Core enforces). `null` means any authenticated
   * session of this plane may be *offered* the destination; Core still authorizes the request.
   */
  capability: string | null;
  /** Availability gate. Most destinations are always available; upload is deployment-gated. */
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
 */
const destinations: readonly NavigationDestination[] = Object.freeze([
  // --- Command Center (CC) ---
  d({ id: "command-center", path: "/command-center", label: "Command Center", section: "Command Center", sectionCode: "CC" }),
  d({ id: "analytics", path: "/analytics", label: "Analytics", section: "Command Center", sectionCode: "CC", capability: "ANALYTICS_READ", searchAliases: ["business value", "roi", "metrics", "kpi"] }),
  d({ id: "pilot-readiness", path: "/pilot-readiness", label: "Pilot Readiness", section: "Command Center", sectionCode: "CC" }),
  d({ id: "pilot-evidence-report", path: "/pilot-readiness/evidence-report", label: "Pilot Evidence Report", section: "Command Center", sectionCode: "CC" }),
  d({ id: "pilot-demo-scenarios", path: "/pilot-readiness/demo-scenarios", label: "Pilot Demo Scenarios", section: "Command Center", sectionCode: "CC" }),
  d({ id: "demo", path: "/demo", label: "Investor Demo", section: "Command Center", sectionCode: "CC", searchAliases: ["sandbox", "investor demo"] }),

  // --- Inbox (IN) ---
  d({ id: "inbox", path: "/inbox", label: "Inbox", section: "Inbox", sectionCode: "IN" }),
  d({ id: "upload", path: "/upload", label: "Upload", section: "Inbox", sectionCode: "IN", availability: "UPLOAD_CAPABILITY_GATED" }),
  d({ id: "documents", path: "/documents", label: "Documents", section: "Inbox", sectionCode: "IN" }),
  d({ id: "messages", path: "/messages", label: "Messages", section: "Inbox", sectionCode: "IN" }),
  d({ id: "extractions", path: "/extractions", label: "Extractions", section: "Inbox", sectionCode: "IN" }),
  d({ id: "processing-jobs", path: "/processing-jobs", label: "Processing Jobs", section: "Inbox", sectionCode: "IN" }),

  // --- Work Queue (WQ) ---
  d({ id: "validation-review", path: "/validation-review", label: "Validation Review", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),
  d({ id: "exception-cockpit", path: "/exception-cockpit", label: "Exception Cockpit", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),
  d({ id: "conversion-review", path: "/conversion-review", label: "Conversion Review", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),
  d({ id: "quote-review", path: "/quote-review", label: "Quote Review", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),
  d({ id: "review-origin-drafts", path: "/workspace/review-drafts", label: "Review-Origin Drafts", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),
  d({ id: "rfq-handoffs", path: "/channels/rfq-handoffs", label: "RFQ Handoffs", section: "Work Queue", sectionCode: "WQ", capability: "REVIEW_READ" }),

  // --- Transactions (TX) ---
  d({ id: "quotes", path: "/quotes", label: "Draft Quotes", section: "Transactions", sectionCode: "TX" }),
  d({ id: "orders", path: "/orders", label: "Draft Orders", section: "Transactions", sectionCode: "TX" }),
  d({ id: "draft-quote-review", path: "/workspace/draft-quotes", label: "Draft Quote Review", section: "Transactions", sectionCode: "TX", capability: "REVIEW_READ" }),
  d({ id: "draft-order-review", path: "/workspace/draft-orders", label: "Draft Order Review", section: "Transactions", sectionCode: "TX", capability: "REVIEW_READ" }),
  d({ id: "order-journey", path: "/order-journey", label: "Order Journey", section: "Transactions", sectionCode: "TX" }),

  // --- Catalog (CA) ---
  d({ id: "customers", path: "/customers", label: "Customers", section: "Catalog", sectionCode: "CA" }),
  d({ id: "products", path: "/products", label: "Products", section: "Catalog", sectionCode: "CA" }),
  d({ id: "inventory", path: "/inventory", label: "Inventory", section: "Catalog", sectionCode: "CA" }),
  d({ id: "pricing", path: "/pricing", label: "Pricing", section: "Catalog", sectionCode: "CA" }),
  d({ id: "imports", path: "/imports", label: "Imports", section: "Catalog", sectionCode: "CA" }),

  // --- Intelligence (AI) ---
  d({ id: "commerce-intelligence", path: "/commerce-intelligence", label: "Commerce Intelligence", section: "Intelligence", sectionCode: "AI" }),
  d({ id: "runtime-control", path: "/runtime-control", label: "Runtime Control Telemetry", section: "Intelligence", sectionCode: "AI" }),
  d({ id: "ai-work", path: "/ai-work", label: "AI Work Assistant", section: "Intelligence", sectionCode: "AI" }),
  d({ id: "reconciliation", path: "/reconciliation", label: "Reconciliation", section: "Intelligence", sectionCode: "AI" }),

  // --- Channels (CH) --- (`/bot/conversations` is a legacy alias of `/bot-conversations`)
  d({ id: "channels", path: "/channels", label: "Channels", section: "Channels", sectionCode: "CH" }),
  d({ id: "bot-conversations", path: "/bot-conversations", label: "Bot / Conversations", section: "Channels", sectionCode: "CH", aliases: ["/bot/conversations"] }),
  d({ id: "messenger-bridge", path: "/messenger-bridge", label: "Messenger Bridge", section: "Channels", sectionCode: "CH" }),
  d({ id: "channel-identities", path: "/channel-identities", label: "Channel Identities", section: "Channels", sectionCode: "CH" }),
  d({ id: "inbound-events", path: "/inbound-events", label: "Inbound Events", section: "Channels", sectionCode: "CH" }),
  d({ id: "webhook-events", path: "/webhook-events", label: "Webhook Events", section: "Channels", sectionCode: "CH" }),
  d({ id: "bot-runtime", path: "/bot-runtime", label: "Bot Runtime", section: "Channels", sectionCode: "CH" }),
  d({ id: "bot-settings", path: "/bot-settings", label: "Bot Settings", section: "Channels", sectionCode: "CH" }),

  // --- Control Center (CT) --- (`/audit` is a legacy alias of the canonical `/audit-log`)
  d({ id: "integrations", path: "/integrations", label: "Integrations", section: "Control Center", sectionCode: "CT" }),
  d({ id: "sync-events", path: "/sync-events", label: "Sync Events", section: "Control Center", sectionCode: "CT" }),
  d({ id: "audit", path: "/audit-log", label: "Audit / Security", section: "Control Center", sectionCode: "CT", aliases: ["/audit"] }),

  // --- Settings (ST) ---
  d({ id: "settings", path: "/settings", label: "Settings", section: "Settings", sectionCode: "ST" }),

  // --- STAFF plane (Operant Support & Maintenance) — never surfaced in tenant nav or palette ---
  d({ id: "internal-support", path: "/internal-support", label: "Internal Support", section: "Operant Support", sectionCode: "SP", plane: "STAFF", capability: "STAFF_INCIDENT_READ", paletteVisible: false, showInPrimaryNav: false }),
  d({ id: "internal-support-operations", path: "/internal-support/operations", label: "Support Operations", section: "Operant Support", sectionCode: "SP", plane: "STAFF", capability: "STAFF_INCIDENT_READ", paletteVisible: false, showInPrimaryNav: false }),

  // --- CUSTOMER plane (buyer-safe) — never surfaced in tenant nav or palette ---
  d({ id: "public-order-tracking", path: "/public/order-tracking", label: "Order Tracking", section: "Customer Portal", sectionCode: "PT", plane: "CUSTOMER", paletteVisible: false, showInPrimaryNav: false })
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
  capability?: string | null;
  availability?: NavigationAvailability;
  aliases?: string[];
  searchAliases?: string[];
  paletteVisible?: boolean;
  showInPrimaryNav?: boolean;
}): NavigationDestination {
  return {
    id: input.id,
    path: input.path,
    label: input.label,
    section: input.section,
    sectionCode: input.sectionCode,
    plane: input.plane ?? "TENANT",
    capability: input.capability ?? null,
    availability: input.availability ?? "AVAILABLE",
    aliases: input.aliases ?? [],
    searchAliases: input.searchAliases ?? [],
    paletteVisible: input.paletteVisible ?? true,
    showInPrimaryNav: input.showInPrimaryNav ?? true
  };
}

/** All registered destinations across every access plane. */
export const navigationDestinations: readonly NavigationDestination[] = destinations;

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

/**
 * Tenant primary navigation destinations. When `capabilities` is provided, destinations whose
 * required capability is absent are omitted from what is *offered* (never a security boundary —
 * Core still authorizes). When omitted, all tenant primary destinations are returned.
 */
export function tenantPrimaryDestinations(
  capabilities?: ReadonlySet<string>
): NavigationDestination[] {
  return destinations.filter((dest) => {
    if (dest.plane !== "TENANT" || !dest.showInPrimaryNav) {
      return false;
    }
    if (capabilities && dest.capability !== null) {
      return capabilities.has(dest.capability);
    }
    return true;
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
    if (capabilities && dest.capability !== null) {
      return capabilities.has(dest.capability);
    }
    return true;
  });
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
