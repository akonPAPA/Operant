import { registeredBffRoutes } from "./bff-route-registry.ts";

export type OidcAccessPlane =
  | "TENANT_USER"
  | "EXTERNAL_CUSTOMER"
  | "SERVICE_ACCOUNT"
  | "OPERANT_SUPPORT";

export type VerifiedOidcPrincipal = Readonly<{
  issuer: string;
  subject: string;
  audience: string;
  email?: string;
  emailVerified?: boolean;
  claims?: Readonly<Record<string, unknown>>;
  tokenExpiresAtEpochSec?: number;
}>;

export type OidcIdentityMappingStatus =
  | "MAPPED"
  | "DENIED_NOT_FOUND"
  | "DENIED_AMBIGUOUS"
  | "DENIED_DISABLED"
  | "DENIED_UNTRUSTED_ISSUER"
  | "DENIED_UNVERIFIED_EMAIL"
  | "DENIED_UNSUPPORTED_PLANE"
  | "DENIED_UNTRUSTED_CLAIM";

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
  mappingVersion: string;
  source: "STATIC_TEST_FIXTURE" | "CONFIGURED_MAPPING" | "PERSISTENT_STORE";
}>;

export type TenantUserSessionProjection = Readonly<{
  accessPlane: "TENANT_USER";
  tenantId: string;
  actorId: string;
  permissions: readonly string[];
  displayName?: string;
  email?: string;
}>;

export type SafeOidcIdentityProjection =
  | TenantUserSessionProjection
  | Readonly<{
      accessPlane: "OPERANT_SUPPORT";
      staffRef: string;
      displayName?: string;
      email?: string;
    }>
  | Readonly<{
      accessPlane: "EXTERNAL_CUSTOMER";
      externalCustomerRef: string;
      displayName?: string;
      email?: string;
    }>
  | Readonly<{
      accessPlane: "SERVICE_ACCOUNT";
      serviceAccountRef: string;
      displayName?: string;
    }>;

export type MappedOidcIdentity = Readonly<{
  status: "MAPPED";
  accessPlane: OidcAccessPlane;
  identityRef: string;
  tenantRef?: string;
  actorRef?: string;
  staffRef?: string;
  serviceAccountRef?: string;
  externalCustomerRef?: string;
  mappingVersion: string;
  source: OidcIdentityMappingRecord["source"];
  safeProjection: SafeOidcIdentityProjection;
}>;

export type DeniedOidcIdentityMapping = Readonly<{
  status: Exclude<OidcIdentityMappingStatus, "MAPPED">;
  denialReason: Exclude<OidcIdentityMappingStatus, "MAPPED">;
}>;

export type OperantIdentityMappingResult = MappedOidcIdentity | DeniedOidcIdentityMapping;

export type OidcIdentityMappingResolverOptions = Readonly<{
  mappings?: readonly OidcIdentityMappingRecord[];
  supportedAccessPlanes?: readonly OidcAccessPlane[];
}>;

const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BOUNDED_TEXT = /^[^\x00-\x1f\x7f]{1,512}$/;
const SAFE_DISPLAY_TEXT = /^[^\x00-\x1f\x7f]{1,160}$/;
const SAFE_EMAIL_TEXT = /^[^\x00-\x1f\x7f]{3,254}$/;
const AUTHORITY_CLAIM_NAMES = new Set([
  "tenantId",
  "actorId",
  "roles",
  "role",
  "permissions",
  "permission",
  "staffUserId",
  "serviceAccountId",
  "approval",
  "approvalStatus"
]);
const DEFAULT_SUPPORTED_ACCESS_PLANES: readonly OidcAccessPlane[] = [
  "TENANT_USER",
  "EXTERNAL_CUSTOMER",
  "OPERANT_SUPPORT"
];
const ALLOWED_BFF_PERMISSIONS = new Set(registeredBffRoutes().map((rule) => rule.permission));

function deny(status: DeniedOidcIdentityMapping["status"]): DeniedOidcIdentityMapping {
  return Object.freeze({ status, denialReason: status });
}

function boundedIdentityText(value: unknown): value is string {
  return typeof value === "string" && BOUNDED_TEXT.test(value.trim());
}

function safeOptionalText(value: string | undefined, pattern: RegExp): string | undefined {
  if (!value) {
    return undefined;
  }
  const trimmed = value.trim();
  return pattern.test(trimmed) ? trimmed : undefined;
}

function hasAuthorityClaim(principal: VerifiedOidcPrincipal): boolean {
  if (!principal.claims) {
    return false;
  }
  return Object.keys(principal.claims).some((name) => AUTHORITY_CLAIM_NAMES.has(name));
}

function principalIsBounded(principal: VerifiedOidcPrincipal): boolean {
  return (
    boundedIdentityText(principal.issuer) &&
    boundedIdentityText(principal.subject) &&
    boundedIdentityText(principal.audience)
  );
}

function mappingMatchesPrincipal(
  principal: VerifiedOidcPrincipal,
  mapping: OidcIdentityMappingRecord
): boolean {
  return (
    principal.issuer === mapping.issuer &&
    principal.subject === mapping.subject &&
    principal.audience === mapping.audience
  );
}

function validPermissionList(permissions: readonly string[] | undefined): permissions is readonly string[] {
  if (!permissions || permissions.length === 0 || permissions.length > 64) {
    return false;
  }
  const unique = new Set(permissions);
  return (
    unique.size === permissions.length &&
    permissions.every(
      (permission) =>
        /^[A-Z][A-Z0-9_]{1,64}$/.test(permission) &&
        ALLOWED_BFF_PERMISSIONS.has(permission) &&
        !permission.startsWith("STAFF_") &&
        !permission.startsWith("SUPPORT_") &&
        !permission.startsWith("INTERNAL_")
    )
  );
}

function emailMatches(principal: VerifiedOidcPrincipal, mapping: OidcIdentityMappingRecord): boolean | "unverified" {
  if (!mapping.requireEmail) {
    return true;
  }
  if (principal.emailVerified !== true) {
    return "unverified";
  }
  return principal.email?.trim().toLowerCase() === mapping.requireEmail.trim().toLowerCase();
}

function validatePlaneShape(mapping: OidcIdentityMappingRecord): boolean {
  if (mapping.accessPlane === "TENANT_USER") {
    return Boolean(
      mapping.tenantRef &&
        UUID_VALUE.test(mapping.tenantRef) &&
        mapping.actorRef &&
        UUID_VALUE.test(mapping.actorRef) &&
        validPermissionList(mapping.bffPermissions) &&
        !mapping.staffRef &&
        !mapping.serviceAccountRef &&
        !mapping.externalCustomerRef
    );
  }
  if (mapping.accessPlane === "OPERANT_SUPPORT") {
    return Boolean(
      mapping.staffRef &&
        UUID_VALUE.test(mapping.staffRef) &&
        !mapping.tenantRef &&
        !mapping.actorRef &&
        !mapping.serviceAccountRef &&
        !mapping.externalCustomerRef &&
        !mapping.bffPermissions
    );
  }
  if (mapping.accessPlane === "SERVICE_ACCOUNT") {
    return Boolean(
      mapping.serviceAccountRef &&
        UUID_VALUE.test(mapping.serviceAccountRef) &&
        !mapping.tenantRef &&
        !mapping.actorRef &&
        !mapping.staffRef &&
        !mapping.externalCustomerRef &&
        !mapping.bffPermissions
    );
  }
  return Boolean(
    mapping.externalCustomerRef &&
      UUID_VALUE.test(mapping.externalCustomerRef) &&
      !mapping.tenantRef &&
      !mapping.actorRef &&
      !mapping.staffRef &&
      !mapping.serviceAccountRef &&
      !mapping.bffPermissions
  );
}

function projectionFor(mapping: OidcIdentityMappingRecord): SafeOidcIdentityProjection | null {
  const displayName = safeOptionalText(mapping.safeDisplayName, SAFE_DISPLAY_TEXT);
  const email = safeOptionalText(mapping.safeEmail, SAFE_EMAIL_TEXT);
  if (mapping.accessPlane === "TENANT_USER" && mapping.tenantRef && mapping.actorRef && mapping.bffPermissions) {
    return Object.freeze({
      accessPlane: "TENANT_USER" as const,
      tenantId: mapping.tenantRef,
      actorId: mapping.actorRef,
      permissions: Object.freeze([...mapping.bffPermissions]),
      ...(displayName ? { displayName } : {}),
      ...(email ? { email } : {})
    });
  }
  if (mapping.accessPlane === "OPERANT_SUPPORT" && mapping.staffRef) {
    return Object.freeze({
      accessPlane: "OPERANT_SUPPORT" as const,
      staffRef: mapping.staffRef,
      ...(displayName ? { displayName } : {}),
      ...(email ? { email } : {})
    });
  }
  if (mapping.accessPlane === "SERVICE_ACCOUNT" && mapping.serviceAccountRef) {
    return Object.freeze({
      accessPlane: "SERVICE_ACCOUNT" as const,
      serviceAccountRef: mapping.serviceAccountRef,
      ...(displayName ? { displayName } : {})
    });
  }
  if (mapping.accessPlane === "EXTERNAL_CUSTOMER" && mapping.externalCustomerRef) {
    return Object.freeze({
      accessPlane: "EXTERNAL_CUSTOMER" as const,
      externalCustomerRef: mapping.externalCustomerRef,
      ...(displayName ? { displayName } : {}),
      ...(email ? { email } : {})
    });
  }
  return null;
}

function identityRefFor(mapping: OidcIdentityMappingRecord): string {
  return mapping.actorRef ?? mapping.staffRef ?? mapping.serviceAccountRef ?? mapping.externalCustomerRef ?? "";
}

function mapped(mapping: OidcIdentityMappingRecord, safeProjection: SafeOidcIdentityProjection): MappedOidcIdentity {
  return Object.freeze({
    status: "MAPPED" as const,
    accessPlane: mapping.accessPlane,
    identityRef: identityRefFor(mapping),
    ...(mapping.tenantRef ? { tenantRef: mapping.tenantRef } : {}),
    ...(mapping.actorRef ? { actorRef: mapping.actorRef } : {}),
    ...(mapping.staffRef ? { staffRef: mapping.staffRef } : {}),
    ...(mapping.serviceAccountRef ? { serviceAccountRef: mapping.serviceAccountRef } : {}),
    ...(mapping.externalCustomerRef ? { externalCustomerRef: mapping.externalCustomerRef } : {}),
    mappingVersion: mapping.mappingVersion,
    source: mapping.source,
    safeProjection
  });
}

export function resolveOidcIdentityMapping(
  principal: VerifiedOidcPrincipal,
  options: OidcIdentityMappingResolverOptions = {}
): OperantIdentityMappingResult {
  if (!principalIsBounded(principal)) {
    return deny("DENIED_NOT_FOUND");
  }
  if (hasAuthorityClaim(principal)) {
    return deny("DENIED_UNTRUSTED_CLAIM");
  }
  const mappings = options.mappings ?? [];
  const issuerMatches = mappings.filter((mapping) => mapping.issuer === principal.issuer);
  if (issuerMatches.length === 0) {
    return deny("DENIED_UNTRUSTED_ISSUER");
  }
  const audienceMatches = issuerMatches.filter((mapping) => mapping.audience === principal.audience);
  if (audienceMatches.length === 0) {
    return deny("DENIED_UNTRUSTED_ISSUER");
  }
  const subjectMatches = audienceMatches.filter((mapping) => mappingMatchesPrincipal(principal, mapping));
  if (subjectMatches.length === 0) {
    return deny("DENIED_NOT_FOUND");
  }
  if (subjectMatches.every((mapping) => !mapping.enabled)) {
    return deny("DENIED_DISABLED");
  }
  const enabledMatches = subjectMatches.filter((mapping) => mapping.enabled);
  const emailFiltered: OidcIdentityMappingRecord[] = [];
  for (const mapping of enabledMatches) {
    const match = emailMatches(principal, mapping);
    if (match === "unverified") {
      return deny("DENIED_UNVERIFIED_EMAIL");
    }
    if (match) {
      emailFiltered.push(mapping);
    }
  }
  if (emailFiltered.length === 0) {
    return deny("DENIED_NOT_FOUND");
  }
  if (emailFiltered.length !== 1) {
    return deny("DENIED_AMBIGUOUS");
  }

  const mapping = emailFiltered[0];
  const supported = new Set(options.supportedAccessPlanes ?? DEFAULT_SUPPORTED_ACCESS_PLANES);
  if (!supported.has(mapping.accessPlane)) {
    return deny("DENIED_UNSUPPORTED_PLANE");
  }
  if (!validatePlaneShape(mapping)) {
    return deny("DENIED_UNSUPPORTED_PLANE");
  }
  const safeProjection = projectionFor(mapping);
  if (!safeProjection) {
    return deny("DENIED_UNSUPPORTED_PLANE");
  }
  return mapped(mapping, safeProjection);
}

export function createStaticOidcIdentityMappingResolver(
  mappings: readonly OidcIdentityMappingRecord[],
  options: Omit<OidcIdentityMappingResolverOptions, "mappings"> = {}
): (principal: VerifiedOidcPrincipal) => OperantIdentityMappingResult {
  const frozenMappings = Object.freeze([...mappings]);
  return (principal) => resolveOidcIdentityMapping(principal, { ...options, mappings: frozenMappings });
}