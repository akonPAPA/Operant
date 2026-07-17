import "server-only";

export const ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV = "ORDERPILOT_OIDC_IDENTITY_MAPPINGS_JSON";
export const MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES = 64 * 1024;
export const MAX_OIDC_IDENTITY_MAPPING_RECORDS = 256;
export const MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS = 64;

export type OidcAccessPlane =
  | "TENANT_USER"
  | "EXTERNAL_CUSTOMER"
  | "OPERANT_SUPPORT"
  | "SERVICE_ACCOUNT";

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
  | Readonly<{
      status:
        | "DENIED_UNTRUSTED_CLAIM"
        | "DENIED_UNTRUSTED_ISSUER"
        | "DENIED_NOT_FOUND"
        | "DENIED_AMBIGUOUS"
        | "DENIED_DISABLED"
        | "DENIED_EMAIL_MISMATCH"
        | "DENIED_UNVERIFIED_EMAIL"
        | "DENIED_UNSUPPORTED_PLANE";
    }>;

export type OidcIdentityMappingSource = Readonly<{
  source: "CONFIGURED_MAPPING";
  mappingCount: number;
  resolveCandidates(
    issuer: string,
    audience: string,
    subject: string
  ): readonly OidcIdentityMappingRecord[];
}>;

export type OidcIdentityMappingSourceErrorCode =
  | "MAPPING_CONFIG_REQUIRED"
  | "MAPPING_CONFIG_EMPTY"
  | "MAPPING_CONFIG_OVERSIZED"
  | "MAPPING_CONFIG_MALFORMED"
  | "MAPPING_CONFIG_INVALID";

export type OidcIdentityMappingSourceResult =
  | Readonly<{ ok: true; source: OidcIdentityMappingSource }>
  | Readonly<{
      ok: false;
      error: Readonly<{ code: OidcIdentityMappingSourceErrorCode; message: string }>;
    }>;

export type ProductionOidcIdentityMappingResolver = (
  principal: VerifiedOidcPrincipal
) => OperantIdentityMappingResult;

export type OidcMappingEnvironment = Record<string, string | undefined>;
export type ConfiguredSourceOptions = Readonly<{ nowEpochSec?: () => number }>;

export const configuredOidcIdentityMappingPolicy = Object.freeze({
  envName: ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV,
  maxBytes: MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES,
  maxRecords: MAX_OIDC_IDENTITY_MAPPING_RECORDS,
  maxPermissions: MAX_OIDC_IDENTITY_MAPPING_PERMISSIONS,
  source: "CONFIGURED_MAPPING" as const,
  serviceAccountBrowserOidcSupported: false
});
