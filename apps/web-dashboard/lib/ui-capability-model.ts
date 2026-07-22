/**
 * UI capability projection model (offer filtering only — never authorization).
 *
 * Core remains authoritative. This module maps allowlisted backend permission strings to
 * allowlisted UI capability identifiers used solely to decide what the tenant shell may *offer*.
 * Unknown backend permissions are ignored. Unknown UI capabilities are denied at filter time.
 */

export const UI_CAPABILITIES = Object.freeze([
  "VIEW_ANALYTICS",
  "VIEW_DOCUMENTS",
  "VIEW_REVIEW_QUEUE",
  "VIEW_VALIDATION",
  "VIEW_CONFIGURATION",
  "VIEW_BOT",
  "VIEW_QUOTES",
  "QUOTE_ACTION",
  "VIEW_CHANGE_REQUESTS",
  "PERFORM_REVIEW_ACTION"
] as const);

export type UiCapability = (typeof UI_CAPABILITIES)[number];

export type CapabilityProjectionStatus = "ALLOWED" | "DENIED" | "UNAVAILABLE";

export type UiCapabilityProjection = Readonly<{
  /** Three-state result: do not collapse UNAVAILABLE into DENIED. */
  status: CapabilityProjectionStatus;
  /** Allowlisted UI capabilities only. Empty when DENIED or UNAVAILABLE. */
  capabilities: readonly UiCapability[];
}>;

const UI_CAPABILITY_SET: ReadonlySet<string> = new Set(UI_CAPABILITIES);

/**
 * Server-owned allowlist: backend permission → UI capability.
 * Unknown permissions ignored. Staff/support/control permissions never appear here.
 */
export const PERMISSION_TO_UI_CAPABILITY: Readonly<Record<string, UiCapability>> = Object.freeze({
  ANALYTICS_READ: "VIEW_ANALYTICS",
  INTAKE_READ: "VIEW_DOCUMENTS",
  REVIEW_READ: "VIEW_REVIEW_QUEUE",
  VALIDATION_READ: "VIEW_VALIDATION",
  REVIEW_ACTION: "PERFORM_REVIEW_ACTION",
  ADMIN_SETTINGS_READ: "VIEW_CONFIGURATION",
  BOT_READ: "VIEW_BOT",
  QUOTE_READ: "VIEW_QUOTES",
  QUOTE_ACTION: "QUOTE_ACTION",
  CHANGE_REQUEST_READ: "VIEW_CHANGE_REQUESTS"
});

export function isUiCapability(value: string): value is UiCapability {
  return UI_CAPABILITY_SET.has(value);
}

/** Backend permissions that map into tenant UI capabilities (for contract tests). */
export function mappedBackendPermissions(): readonly string[] {
  return Object.freeze(Object.keys(PERMISSION_TO_UI_CAPABILITY));
}

/**
 * Project backend permissions into allowlisted UI capabilities.
 * Staff/support/control permissions never map into tenant UI capabilities.
 */
export function projectPermissionsToUiCapabilities(
  permissions: readonly string[]
): ReadonlySet<UiCapability> {
  const out = new Set<UiCapability>();
  for (const permission of permissions) {
    const mapped = PERMISSION_TO_UI_CAPABILITY[permission];
    if (mapped) {
      out.add(mapped);
    }
  }
  return out;
}

export function projectionFromPermissions(
  permissions: readonly string[] | null | undefined
): UiCapabilityProjection {
  if (!permissions) {
    return { status: "DENIED", capabilities: [] };
  }
  const capabilities = [...projectPermissionsToUiCapabilities(permissions)];
  if (capabilities.length === 0) {
    // Authenticated session with no mappable tenant UI capabilities — offer only null-gated routes.
    return { status: "ALLOWED", capabilities: [] };
  }
  return { status: "ALLOWED", capabilities };
}

export function unavailableProjection(): UiCapabilityProjection {
  return { status: "UNAVAILABLE", capabilities: [] };
}

export function deniedProjection(): UiCapabilityProjection {
  return { status: "DENIED", capabilities: [] };
}

/** Parse a JSON body into a projection; malformed shape → null (caller maps to CONTRACT/UNAVAILABLE). */
export function parseUiCapabilityProjection(body: unknown): UiCapabilityProjection | null {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return null;
  }
  const keys = Object.keys(body as object);
  if (keys.length !== 2 || !keys.includes("status") || !keys.includes("capabilities")) {
    // Reject authority/leak fields (tenantId, permissions, …) and any other extras.
    return null;
  }
  const record = body as { status?: unknown; capabilities?: unknown };
  if (
    record.status !== "ALLOWED" &&
    record.status !== "DENIED" &&
    record.status !== "UNAVAILABLE"
  ) {
    return null;
  }
  if (!Array.isArray(record.capabilities)) {
    return null;
  }
  if (record.capabilities.length > UI_CAPABILITIES.length) {
    return null;
  }
  const capabilities: UiCapability[] = [];
  for (const entry of record.capabilities) {
    if (typeof entry !== "string" || !isUiCapability(entry)) {
      return null;
    }
    capabilities.push(entry);
  }
  if (record.status !== "ALLOWED" && capabilities.length > 0) {
    return null;
  }
  return { status: record.status, capabilities };
}

/**
 * Capability set used for offer filtering.
 * UNAVAILABLE → empty set (fail closed on gated destinations; callers keep universally safe routes).
 * DENIED → empty set.
 * ALLOWED → projected capabilities.
 */
export function offerFilterCapabilities(
  projection: UiCapabilityProjection
): ReadonlySet<UiCapability> | undefined {
  if (projection.status === "ALLOWED") {
    return new Set(projection.capabilities);
  }
  return new Set();
}
