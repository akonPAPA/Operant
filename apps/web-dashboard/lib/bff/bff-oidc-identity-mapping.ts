import "server-only";

export * from "./bff-oidc-identity-types.ts";

import { normalizeConfiguredOidcMappingRecord } from "./bff-oidc-identity-record.ts";
import { readConfiguredOidcIdentityMappingSource } from "./bff-oidc-identity-source.ts";
import {
  type ConfiguredSourceOptions,
  type OidcAccessPlane,
  type OidcIdentityMappingRecord,
  type OidcMappingEnvironment,
  type OperantIdentityMappingResult,
  type ProductionOidcIdentityMappingResolver,
  type VerifiedOidcPrincipal
} from "./bff-oidc-identity-types.ts";

export { readConfiguredOidcIdentityMappingSource } from "./bff-oidc-identity-source.ts";

const AUTHORITY_CLAIMS = new Set([
  "tenantId", "tenant_id", "actorId", "actor_id", "userId", "user_id",
  "staffUserId", "staff_user_id", "serviceAccountId", "service_account_id",
  "permissions", "permission", "roles", "role", "groups", "group", "authorities",
  "scope", "scp", "realm_access", "resource_access", "approval", "approvalStatus",
  "approval_status", "approvedBy", "approved_by", "riskLevel", "risk_level", "margin",
  "stock", "priceAuthority", "executionStatus", "supportRole", "support_role",
  "staffRole", "staff_role", "externalWriteAuthority"
]);

function hasAuthorityClaim(claims: Readonly<Record<string, unknown>>): boolean {
  return Object.keys(claims).some((claim) => AUTHORITY_CLAIMS.has(claim));
}

export function resolveOidcIdentityMapping(
  principal: VerifiedOidcPrincipal,
  options?: {
    mappings: readonly OidcIdentityMappingRecord[];
    supportedAccessPlanes?: readonly OidcAccessPlane[];
  }
): OperantIdentityMappingResult {
  if (!options) return { status: "DENIED_UNTRUSTED_ISSUER" };
  if (hasAuthorityClaim(principal.claims)) return { status: "DENIED_UNTRUSTED_CLAIM" };
  const candidates = options.mappings.filter(
    (mapping) =>
      mapping.issuer === principal.issuer &&
      mapping.audience === principal.audience &&
      mapping.subject === principal.subject
  );
  if (candidates.length === 0) return { status: "DENIED_NOT_FOUND" };
  if (candidates.length > 1) return { status: "DENIED_AMBIGUOUS" };
  const mapping = candidates[0];
  if (!mapping.enabled) return { status: "DENIED_DISABLED" };
  if (mapping.requireEmail && principal.emailVerified !== true) {
    return { status: "DENIED_UNVERIFIED_EMAIL" };
  }
  if (mapping.requireEmail && mapping.requireEmail.toLowerCase() !== principal.email?.toLowerCase()) {
    return { status: "DENIED_EMAIL_MISMATCH" };
  }
  if (options.supportedAccessPlanes && !options.supportedAccessPlanes.includes(mapping.accessPlane)) {
    return { status: "DENIED_UNSUPPORTED_PLANE" };
  }

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

  if (mapping.accessPlane === "OPERANT_SUPPORT") {
    return Object.freeze({
      status: "MAPPED" as const,
      source: "CONFIGURED_MAPPING" as const,
      mappingVersion: mapping.mappingVersion,
      accessPlane: "OPERANT_SUPPORT" as const,
      staffRef: mapping.staffRef,
      safeProjection: Object.freeze({
        accessPlane: "OPERANT_SUPPORT",
        staffRef: mapping.staffRef,
        ...(mapping.safeDisplayName ? { displayName: mapping.safeDisplayName } : {}),
        ...(mapping.safeEmail ? { email: mapping.safeEmail } : {})
      })
    });
  }

  if (mapping.accessPlane === "EXTERNAL_CUSTOMER") {
    return Object.freeze({
      status: "MAPPED" as const,
      source: "CONFIGURED_MAPPING" as const,
      mappingVersion: mapping.mappingVersion,
      accessPlane: "EXTERNAL_CUSTOMER" as const,
      externalCustomerRef: mapping.externalCustomerRef,
      safeProjection: Object.freeze({
        accessPlane: "EXTERNAL_CUSTOMER",
        externalCustomerRef: mapping.externalCustomerRef,
        ...(mapping.safeDisplayName ? { displayName: mapping.safeDisplayName } : {}),
        ...(mapping.safeEmail ? { email: mapping.safeEmail } : {})
      })
    });
  }

  return { status: "DENIED_UNSUPPORTED_PLANE" };
}

export function createStaticOidcIdentityMappingResolver(
  rawMappings: readonly Record<string, unknown>[]
): ProductionOidcIdentityMappingResolver {
  const normalized = rawMappings.map((raw) =>
    normalizeConfiguredOidcMappingRecord({ ...raw, source: "CONFIGURED_MAPPING" })
  );
  return (principal) => {
    const issuerAudienceMatches = rawMappings.filter(
      (mapping) => mapping.issuer === principal.issuer && mapping.audience === principal.audience
    );
    if (issuerAudienceMatches.length === 0) return { status: "DENIED_UNTRUSTED_ISSUER" };
    const subjectIndexes = rawMappings
      .map((mapping, index) => ({ mapping, index }))
      .filter(({ mapping }) =>
        mapping.issuer === principal.issuer &&
        mapping.audience === principal.audience &&
        mapping.subject === principal.subject
      );
    if (subjectIndexes.length === 0) return { status: "DENIED_NOT_FOUND" };
    if (subjectIndexes.some(({ index }) => !normalized[index])) {
      return { status: "DENIED_UNSUPPORTED_PLANE" };
    }
    return resolveOidcIdentityMapping(principal, {
      mappings: subjectIndexes.map(({ index }) => normalized[index] as OidcIdentityMappingRecord),
      supportedAccessPlanes: ["TENANT_USER", "EXTERNAL_CUSTOMER", "OPERANT_SUPPORT"]
    });
  };
}

export function createProductionOidcIdentityMappingResolver(
  env: OidcMappingEnvironment = process.env,
  options: ConfiguredSourceOptions = {}
): ProductionOidcIdentityMappingResolver {
  const sourceResult = readConfiguredOidcIdentityMappingSource(env, options);
  if (!sourceResult.ok) return () => ({ status: "DENIED_UNTRUSTED_ISSUER" });
  const source = sourceResult.source;
  return (principal) => resolveOidcIdentityMapping(principal, {
    mappings: source.resolveCandidates(principal.issuer, principal.audience, principal.subject),
    supportedAccessPlanes: ["TENANT_USER", "EXTERNAL_CUSTOMER", "OPERANT_SUPPORT"]
  });
}

export function createMemoizedProductionOidcIdentityMappingResolverLoader(
  env: OidcMappingEnvironment = process.env,
  options: ConfiguredSourceOptions = {}
): () => ProductionOidcIdentityMappingResolver {
  let resolver: ProductionOidcIdentityMappingResolver | undefined;
  return () => {
    if (!resolver) resolver = createProductionOidcIdentityMappingResolver(env, options);
    return resolver;
  };
}

const productionResolverLoader = createMemoizedProductionOidcIdentityMappingResolverLoader();

export function getProductionOidcIdentityMappingResolver(): ProductionOidcIdentityMappingResolver {
  return productionResolverLoader();
}
