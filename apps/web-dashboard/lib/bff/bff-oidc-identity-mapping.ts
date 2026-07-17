import "server-only";

import { registeredBffRoutes } from "./bff-route-registry.ts";

export const ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV = "ORDERPILOT_OIDC_IDENTITY_MAPPINGS_JSON";
export const MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES = 64 * 1024;
export const MAX_OIDC_IDENTITY_MAPPING_RECORDS = 256;
export const MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS = 64;

export type OidcAccessPlane = "TENANT_USER" | "EXTERNAL_CUSTOMER" | "OPERANT_SUPPORT" | "SERVICE_ACCOUNT";

export type VerifiedOidcPrincipal = Readonly<{
  issuer: string;
  subject: string;
  audience: string;
  email?: string;
  emailVerified?: boolean;
  claims: Readonly<Record<string, unknown>>;
  tokenExpiresAtEpochSec: number;
}>;

export type OidcIdentityMappingRecord = Readonly<{
  issuer: string;
  subject: string;
  audience: string;
  enabled: boolean;
  accessPlane: OidcAccessPlane;
  tenantRef?: string;
  actorRef?: string;
  staffRef?: string;
  serviceAccountRef?: string;
  externalCustomerRef?: string;
  bffPermissions?: readonly string[];
  safeDisplayName?: string;
  safeEmail?: string;
  requireEmail?: string;
  validFromEpochSec?: number;
  validUntilEpochSec?: number;
  mappingVersion: string;
  source: "CONFIGURED_MAPPING";
}>;

export type OperantIdentityMappingResult =
  | Readonly<{
      status: "MAPPED";
      source: "CONFIGURED_MAPPING";
      mappingVersion: string;
      accessPlane: OidcAccessPlane;
      tenantRef?: string;
      actorRef?: string;
      staffRef?: string;
      serviceAccountRef?: string;
      externalCustomerRef?: string;
      safeProjection: Readonly<Record<string, unknown>>;
    }>
  | Readonly<{ status: "DENIED_UNTRUSTED_CLAIM" | "DENIED_UNTRUSTED_ISSUER" | "DENIED_NOT_FOUND" | "DENIED_AMBIGUOUS" | "DENIED_DISABLED" | "DENIED_EMAIL_MISMATCH" | "DENIED_UNSUPPORTED_PLANE" }>;

export type OidcIdentityMappingSource = Readonly<{
  source: "CONFIGURED_MAPPING";
  mappingCount: number;
  resolveCandidates(issuer: string, audience: string, subject: string): readonly OidcIdentityMappingRecord[];
}>;

export type OidcIdentityMappingSourceErrorCode =
  | "MAPPING_CONFIG_REQUIRED"
  | "MAPPING_CONFIG_EMPTY"
  | "MAPPING_CONFIG_OVERSIZED"
  | "MAPPING_CONFIG_MALFORMED"
  | "MAPPING_CONFIG_INVALID";

export type OidcIdentityMappingSourceResult =
  | Readonly<{ ok: true; source: OidcIdentityMappingSource }>
  | Readonly<{ ok: false; error: Readonly<{ code: OidcIdentityMappingSourceErrorCode; message: string }> }>;

export type ProductionOidcIdentityMappingResolver = (principal: VerifiedOidcPrincipal) => OperantIdentityMappingResult;

type EnvironmentValues = Record<string, string | undefined>;
type ConfiguredSourceOptions = Readonly<{ nowEpochSec?: () => number }>;
type MappingIndex = ReadonlyMap<string, ReadonlyMap<string, ReadonlyMap<string, readonly OidcIdentityMappingRecord[]>>>;

const EMPTY_MAPPINGS: readonly OidcIdentityMappingRecord[] = Object.freeze([]);
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BOUNDED_TEXT = /^[^\x00-\x1f\x7f]{1,512}$/;
const SAFE_DISPLAY_TEXT = /^[^\x00-\x1f\x7f]{1,160}$/;
const SAFE_EMAIL_TEXT = /^[^\x00-\x1f\x7f]{3,254}$/;
const MAPPING_VERSION_VALUE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const SUPPORTED_CONFIGURED_ACCESS_PLANES = new Set<OidcAccessPlane>(["TENANT_USER", "EXTERNAL_CUSTOMER", "OPERANT_SUPPORT"]);
const TENANT_FORBIDDEN_PERMISSION_PREFIXES = ["STAFF_", "SUPPORT_", "INTERNAL_"];
const ALLOWED_BFF_PERMISSIONS = new Set(registeredBffRoutes().map((rule) => rule.permission));
const AUTHORITY_CLAIMS = new Set([
  "tenantId", "tenant_id", "actorId", "actor_id", "userId", "user_id", "staffUserId", "staff_user_id",
  "serviceAccountId", "service_account_id", "permissions", "permission", "roles", "role", "groups", "group",
  "authorities", "scope", "scp", "realm_access", "resource_access", "approval", "approvalStatus", "approval_status",
  "approvedBy", "approved_by", "riskLevel", "risk_level", "margin", "stock", "priceAuthority", "executionStatus",
  "supportRole", "support_role", "staffRole", "staff_role", "externalWriteAuthority"
]);
const RECORD_FIELDS = new Set([
  "issuer", "subject", "audience", "enabled", "accessPlane", "tenantRef", "actorRef", "staffRef", "serviceAccountRef",
  "externalCustomerRef", "bffPermissions", "safeDisplayName", "safeEmail", "requireEmail", "validFromEpochSec",
  "validUntilEpochSec", "mappingVersion", "source"
]);

export const configuredOidcIdentityMappingPolicy = Object.freeze({
  envName: ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV,
  maxBytes: MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES,
  maxRecords: MAX_OIDC_IDENTITY_MAPPING_RECORDS,
  maxPermissions: MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS,
  source: "CONFIGURED_MAPPING" as const,
  serviceAccountBrowserOidcSupported: false
});

function fail(code: OidcIdentityMappingSourceErrorCode): OidcIdentityMappingSourceResult {
  return Object.freeze({ ok: false as const, error: Object.freeze({ code, message: "OIDC identity mapping source is unavailable." }) });
}

function rawEnvValue(env: EnvironmentValues): string | undefined {
  const value = env[ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV];
  return typeof value === "string" && value.trim() ? value : undefined;
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function boundedString(value: unknown, pattern = BOUNDED_TEXT): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return pattern.test(trimmed) ? trimmed : null;
}

function optionalBoundedString(value: unknown, pattern: RegExp): string | undefined | null {
  return value === undefined ? undefined : boundedString(value, pattern);
}

function optionalEpochSecond(value: unknown): number | undefined | null {
  if (value === undefined) return undefined;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function optionalUuid(value: unknown): string | undefined | null {
  if (value === undefined) return undefined;
  return typeof value === "string" && UUID_VALUE.test(value.trim()) ? value.trim() : null;
}

function validTenantPermissions(value: unknown): readonly string[] | null {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS) return null;
  const normalized: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") return null;
    const permission = item.trim();
    if (!/^[A-Z][A-Z0-9_]{1,64}$/.test(permission) || !ALLOWED_BFF_PERMISSIONS.has(permission) || TENANT_FORBIDDEN_PERMISSION_PREFIXES.some((prefix) => permission.startsWith(prefix))) {
      return null;
    }
    normalized.push(permission);
  }
  return new Set(normalized).size === normalized.length ? Object.freeze(normalized) : null;
}

function noValue(...values: readonly unknown[]): boolean {
  return values.every((value) => value === undefined);
}

function validPlaneShape(record: OidcIdentityMappingRecord): boolean {
  if (record.accessPlane === "TENANT_USER") {
    return Boolean(record.tenantRef && record.actorRef && record.bffPermissions && noValue(record.staffRef, record.serviceAccountRef, record.externalCustomerRef));
  }
  if (record.accessPlane === "OPERANT_SUPPORT") {
    return Boolean(record.staffRef && noValue(record.tenantRef, record.actorRef, record.serviceAccountRef, record.externalCustomerRef, record.bffPermissions));
  }
  if (record.accessPlane === "EXTERNAL_CUSTOMER") {
    return Boolean(record.externalCustomerRef && noValue(record.tenantRef, record.actorRef, record.staffRef, record.serviceAccountRef, record.bffPermissions));
  }
  return false;
}

function normalizeRecord(value: unknown): OidcIdentityMappingRecord | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const input = value as Record<string, unknown>;
  if (!Object.keys(input).every((field) => RECORD_FIELDS.has(field))) return null;

  const issuer = boundedString(input.issuer);
  const subject = boundedString(input.subject);
  const audience = boundedString(input.audience);
  const accessPlane = input.accessPlane;
  const mappingVersion = boundedString(input.mappingVersion, MAPPING_VERSION_VALUE);
  const safeDisplayName = optionalBoundedString(input.safeDisplayName, SAFE_DISPLAY_TEXT);
  const safeEmail = optionalBoundedString(input.safeEmail, SAFE_EMAIL_TEXT);
  const requireEmail = optionalBoundedString(input.requireEmail, SAFE_EMAIL_TEXT);
  const validFromEpochSec = optionalEpochSecond(input.validFromEpochSec);
  const validUntilEpochSec = optionalEpochSecond(input.validUntilEpochSec);
  const source = input.source;

  if (!issuer || !subject || !audience || typeof input.enabled !== "boolean" || typeof accessPlane !== "string" || !SUPPORTED_CONFIGURED_ACCESS_PLANES.has(accessPlane as OidcAccessPlane) || !mappingVersion || safeDisplayName === null || safeEmail === null || requireEmail === null || validFromEpochSec === null || validUntilEpochSec === null || (validFromEpochSec !== undefined && validUntilEpochSec !== undefined && validUntilEpochSec <= validFromEpochSec) || (source !== undefined && source !== "CONFIGURED_MAPPING")) {
    return null;
  }

  const tenantRef = optionalUuid(input.tenantRef);
  const actorRef = optionalUuid(input.actorRef);
  const staffRef = optionalUuid(input.staffRef);
  const serviceAccountRef = optionalUuid(input.serviceAccountRef);
  const externalCustomerRef = optionalUuid(input.externalCustomerRef);
  const bffPermissions = input.bffPermissions === undefined ? undefined : validTenantPermissions(input.bffPermissions);
  if (tenantRef === null || actorRef === null || staffRef === null || serviceAccountRef === null || externalCustomerRef === null || bffPermissions === null) return null;

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

function mappingActiveAt(record: OidcIdentityMappingRecord, nowEpochSec: number): boolean {
  if (record.validFromEpochSec !== undefined && nowEpochSec < record.validFromEpochSec) return false;
  if (record.validUntilEpochSec !== undefined && nowEpochSec >= record.validUntilEpochSec) return false;
  return true;
}

function indexRecords(records: readonly OidcIdentityMappingRecord[]): MappingIndex | null {
  const issuerIndex = new Map<string, Map<string, Map<string, readonly OidcIdentityMappingRecord[]>>>();
  for (const record of records) {
    let audienceIndex = issuerIndex.get(record.issuer);
    if (!audienceIndex) { audienceIndex = new Map(); issuerIndex.set(record.issuer, audienceIndex); }
    let subjectIndex = audienceIndex.get(record.audience);
    if (!subjectIndex) { subjectIndex = new Map(); audienceIndex.set(record.audience, subjectIndex); }
    if (subjectIndex.has(record.subject)) return null;
    subjectIndex.set(record.subject, Object.freeze([record]));
  }
  return issuerIndex;
}

function createSource(records: readonly OidcIdentityMappingRecord[], nowEpochSec: () => number): OidcIdentityMappingSource | null {
  const index = indexRecords(records);
  if (!index) return null;
  return Object.freeze({
    source: "CONFIGURED_MAPPING" as const,
    mappingCount: records.length,
    resolveCandidates(issuer: string, audience: string, subject: string): readonly OidcIdentityMappingRecord[] {
      const candidates = index.get(issuer)?.get(audience)?.get(subject) ?? EMPTY_MAPPINGS;
      const now = nowEpochSec();
      const active = candidates.filter((record) => mappingActiveAt(record, now));
      return active.length === candidates.length ? candidates : Object.freeze(active);
    }
  });
}

function extractMappings(parsed: unknown): unknown[] | null {
  if (Array.isArray(parsed)) return parsed;
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
    const input = parsed as Record<string, unknown>;
    if (Object.keys(input).length === 1 && Array.isArray(input.mappings)) return input.mappings;
  }
  return null;
}

export function readConfiguredOidcIdentityMappingSource(env: EnvironmentValues = process.env, options: ConfiguredSourceOptions = {}): OidcIdentityMappingSourceResult {
  const raw = rawEnvValue(env);
  if (!raw) return fail("MAPPING_CONFIG_REQUIRED");
  if (byteLength(raw) > MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES) return fail("MAPPING_CONFIG_OVERSIZED");
  let parsed: unknown;
  try { parsed = JSON.parse(raw); } catch { return fail("MAPPING_CONFIG_MALFORMED"); }
  const rawMappings = extractMappings(parsed);
  if (!rawMappings) return fail("MAPPING_CONFIG_MALFORMED");
  if (rawMappings.length === 0) return fail("MAPPING_CONFIG_EMPTY");
  if (rawMappings.length > MAX_OIDC_IDENTITY_MAPPING_RECORDS) return fail("MAPPING_CONFIG_INVALID");
  const records: OidcIdentityMappingRecord[] = [];
  for (const rawMapping of rawMappings) {
    const record = normalizeRecord(rawMapping);
    if (!record) return fail("MAPPING_CONFIG_INVALID");
    records.push(record);
  }
  const source = createSource(Object.freeze(records), options.nowEpochSec ?? (() => Math.floor(Date.now() / 1000)));
  return source ? Object.freeze({ ok: true as const, source }) : fail("MAPPING_CONFIG_INVALID");
}

function hasAuthorityClaim(claims: Readonly<Record<string, unknown>>): boolean {
  return Object.keys(claims).some((claim) => AUTHORITY_CLAIMS.has(claim));
}

export function resolveOidcIdentityMapping(principal: VerifiedOidcPrincipal, options: { mappings: readonly OidcIdentityMappingRecord[]; supportedAccessPlanes?: readonly OidcAccessPlane[] }): OperantIdentityMappingResult {
  if (hasAuthorityClaim(principal.claims)) return { status: "DENIED_UNTRUSTED_CLAIM" };
  const candidates = options.mappings.filter((mapping) => mapping.issuer === principal.issuer && mapping.audience === principal.audience && mapping.subject === principal.subject);
  if (candidates.length === 0) return { status: "DENIED_NOT_FOUND" };
  if (candidates.length > 1) return { status: "DENIED_AMBIGUOUS" };
  const mapping = candidates[0];
  if (!mapping.enabled) return { status: "DENIED_DISABLED" };
  if (mapping.requireEmail && mapping.requireEmail.toLowerCase() !== principal.email?.toLowerCase()) return { status: "DENIED_EMAIL_MISMATCH" };
  if (options.supportedAccessPlanes && !options.supportedAccessPlanes.includes(mapping.accessPlane)) return { status: "DENIED_UNSUPPORTED_PLANE" };
  if (mapping.accessPlane === "TENANT_USER") {
    return Object.freeze({
      status: "MAPPED" as const,
      source: "CONFIGURED_MAPPING" as const,
      mappingVersion: mapping.mappingVersion,
      accessPlane: "TENANT_USER" as const,
      tenantRef: mapping.tenantRef,
      actorRef: mapping.actorRef,
      safeProjection: Object.freeze({
        accessPlane: "TENANT_USER",
        tenantId: mapping.tenantRef,
        actorId: mapping.actorRef,
        permissions: Object.freeze([...(mapping.bffPermissions ?? [])]),
        ...(mapping.safeDisplayName ? { displayName: mapping.safeDisplayName } : {}),
        ...(mapping.safeEmail ? { email: mapping.safeEmail } : {})
      })
    });
  }
  return Object.freeze({
    status: "MAPPED" as const,
    source: "CONFIGURED_MAPPING" as const,
    mappingVersion: mapping.mappingVersion,
    accessPlane: mapping.accessPlane,
    ...(mapping.staffRef ? { staffRef: mapping.staffRef } : {}),
    ...(mapping.serviceAccountRef ? { serviceAccountRef: mapping.serviceAccountRef } : {}),
    ...(mapping.externalCustomerRef ? { externalCustomerRef: mapping.externalCustomerRef } : {}),
    safeProjection: Object.freeze({ accessPlane: mapping.accessPlane })
  });
}

export function createProductionOidcIdentityMappingResolver(env: EnvironmentValues = process.env, options: ConfiguredSourceOptions = {}): ProductionOidcIdentityMappingResolver {
  const sourceResult = readConfiguredOidcIdentityMappingSource(env, options);
  if (!sourceResult.ok) {
    return () => ({ status: "DENIED_UNTRUSTED_ISSUER" });
  }
  const source = sourceResult.source;
  return (principal) => resolveOidcIdentityMapping(principal, {
    mappings: source.resolveCandidates(principal.issuer, principal.audience, principal.subject),
    supportedAccessPlanes: ["TENANT_USER", "EXTERNAL_CUSTOMER", "OPERANT_SUPPORT"]
  });
}

export function createMemoizedProductionOidcIdentityMappingResolverLoader(env: EnvironmentValues = process.env, options: ConfiguredSourceOptions = {}): () => ProductionOidcIdentityMappingResolver {
  let resolver: ProductionOidcIdentityMappingResolver | undefined;
  return () => {
    if (!resolver) resolver = createProductionOidcIdentityMappingResolver(env, options);
    return resolver;
  };
}

const productionOidcIdentityMappingResolverLoader = createMemoizedProductionOidcIdentityMappingResolverLoader();

export function getProductionOidcIdentityMappingResolver(): ProductionOidcIdentityMappingResolver {
  return productionOidcIdentityMappingResolverLoader();
}
