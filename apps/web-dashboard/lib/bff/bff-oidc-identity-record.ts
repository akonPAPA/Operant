import "server-only";

import { registeredBffRoutes } from "./bff-route-registry.ts";
import {
  MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS,
  type OidcAccessPlane,
  type OidcIdentityMappingRecord
} from "./bff-oidc-identity-types.ts";

const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BOUNDED_TEXT = /^[^\x00-\x1f\x7f]{1,512}$/;
const SAFE_DISPLAY_TEXT = /^[^\x00-\x1f\x7f]{1,160}$/;
const SAFE_EMAIL_TEXT = /^[^\x00-\x1f\x7f]{3,254}$/;
const MAPPING_VERSION_VALUE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const SUPPORTED_PLANES = new Set<OidcAccessPlane>([
  "TENANT_USER",
  "EXTERNAL_CUSTOMER",
  "OPERANT_SUPPORT"
]);
const TENANT_FORBIDDEN_PERMISSION_PREFIXES = ["STAFF_", "SUPPORT_", "ADMIN_", "INTERNAL_"];
const ALLOWED_BFF_PERMISSIONS = new Set(registeredBffRoutes().map((rule) => rule.permission));
const RECORD_FIELDS = new Set([
  "issuer", "subject", "audience", "enabled", "accessPlane", "tenantRef", "actorRef",
  "staffRef", "serviceAccountRef", "externalCustomerRef", "bffPermissions", "safeDisplayName",
  "safeEmail", "requireEmail", "validFromEpochSec", "validUntilEpochSec", "mappingVersion", "source"
]);

function boundedString(value: unknown, pattern = BOUNDED_TEXT): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return pattern.test(trimmed) ? trimmed : null;
}

function optionalString(value: unknown, pattern: RegExp): string | undefined | null {
  return value === undefined ? undefined : boundedString(value, pattern);
}

function optionalEpoch(value: unknown): number | undefined | null {
  if (value === undefined) return undefined;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function optionalUuid(value: unknown): string | undefined | null {
  if (value === undefined) return undefined;
  return typeof value === "string" && UUID_VALUE.test(value.trim()) ? value.trim() : null;
}

function tenantPermissions(value: unknown): readonly string[] | null {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS) {
    return null;
  }
  const normalized: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") return null;
    const permission = item.trim();
    if (
      !/^[A-Z][A-Z0-9_]{1,64}$/.test(permission) ||
      !ALLOWED_BFF_PERMISSIONS.has(permission) ||
      TENANT_FORBIDDEN_PERMISSION_PREFIXES.some((prefix) => permission.startsWith(prefix))
    ) {
      return null;
    }
    normalized.push(permission);
  }
  return new Set(normalized).size === normalized.length ? Object.freeze(normalized) : null;
}

function absent(...values: readonly unknown[]): boolean {
  return values.every((value) => value === undefined);
}

function validPlaneShape(record: OidcIdentityMappingRecord): boolean {
  if (record.accessPlane === "TENANT_USER") {
    return Boolean(
      record.tenantRef &&
      record.actorRef &&
      record.bffPermissions &&
      absent(record.staffRef, record.serviceAccountRef, record.externalCustomerRef)
    );
  }
  if (record.accessPlane === "OPERANT_SUPPORT") {
    return Boolean(
      record.staffRef &&
      absent(record.tenantRef, record.actorRef, record.serviceAccountRef, record.externalCustomerRef, record.bffPermissions)
    );
  }
  if (record.accessPlane === "EXTERNAL_CUSTOMER") {
    return Boolean(
      record.externalCustomerRef &&
      absent(record.tenantRef, record.actorRef, record.staffRef, record.serviceAccountRef, record.bffPermissions)
    );
  }
  return false;
}

export function normalizeConfiguredOidcMappingRecord(value: unknown): OidcIdentityMappingRecord | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const input = value as Record<string, unknown>;
  if (!Object.keys(input).every((field) => RECORD_FIELDS.has(field))) return null;

  const issuer = boundedString(input.issuer);
  const subject = boundedString(input.subject);
  const audience = boundedString(input.audience);
  const accessPlane = input.accessPlane;
  const mappingVersion = boundedString(input.mappingVersion, MAPPING_VERSION_VALUE);
  const safeDisplayName = optionalString(input.safeDisplayName, SAFE_DISPLAY_TEXT);
  const safeEmail = optionalString(input.safeEmail, SAFE_EMAIL_TEXT);
  const requireEmail = optionalString(input.requireEmail, SAFE_EMAIL_TEXT);
  const validFromEpochSec = optionalEpoch(input.validFromEpochSec);
  const validUntilEpochSec = optionalEpoch(input.validUntilEpochSec);
  if (
    !issuer || !subject || !audience || typeof input.enabled !== "boolean" ||
    typeof accessPlane !== "string" || !SUPPORTED_PLANES.has(accessPlane as OidcAccessPlane) ||
    !mappingVersion || safeDisplayName === null || safeEmail === null || requireEmail === null ||
    validFromEpochSec === null || validUntilEpochSec === null ||
    (validFromEpochSec !== undefined && validUntilEpochSec !== undefined && validUntilEpochSec <= validFromEpochSec) ||
    (input.source !== undefined && input.source !== "CONFIGURED_MAPPING")
  ) {
    return null;
  }

  const tenantRef = optionalUuid(input.tenantRef);
  const actorRef = optionalUuid(input.actorRef);
  const staffRef = optionalUuid(input.staffRef);
  const serviceAccountRef = optionalUuid(input.serviceAccountRef);
  const externalCustomerRef = optionalUuid(input.externalCustomerRef);
  const bffPermissions = input.bffPermissions === undefined ? undefined : tenantPermissions(input.bffPermissions);
  if (
    tenantRef === null || actorRef === null || staffRef === null || serviceAccountRef === null ||
    externalCustomerRef === null || bffPermissions === null
  ) {
    return null;
  }

  const record: OidcIdentityMappingRecord = Object.freeze({
    issuer,
    subject,
    audience,
    enabled: input.enabled,
    accessPlane: accessPlane as OidcAccessPlane,
    ...(tenantRef ? { tenantRef } : {}),
    ...(actorRef ? { actorRef } : {}),
    ...(staffRef ? { staffRef } : {}),
    ...(serviceAccountRef ? { serviceAccountRef } : {}),
    ...(externalCustomerRef ? { externalCustomerRef } : {}),
    ...(bffPermissions ? { bffPermissions } : {}),
    ...(safeDisplayName ? { safeDisplayName } : {}),
    ...(safeEmail ? { safeEmail } : {}),
    ...(requireEmail ? { requireEmail } : {}),
    ...(validFromEpochSec !== undefined ? { validFromEpochSec } : {}),
    ...(validUntilEpochSec !== undefined ? { validUntilEpochSec } : {}),
    mappingVersion,
    source: "CONFIGURED_MAPPING"
  });
  return validPlaneShape(record) ? record : null;
}
