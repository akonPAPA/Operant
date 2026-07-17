import "server-only";

import { normalizeConfiguredOidcMappingRecord } from "./bff-oidc-identity-record.ts";
import {
  MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES,
  MAX_OIDC_IDENTITY_MAPPING_RECORDS,
  ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV,
  type ConfiguredSourceOptions,
  type OidcIdentityMappingRecord,
  type OidcIdentityMappingSource,
  type OidcIdentityMappingSourceErrorCode,
  type OidcIdentityMappingSourceResult,
  type OidcMappingEnvironment
} from "./bff-oidc-identity-types.ts";

type MappingIndex = ReadonlyMap<
  string,
  ReadonlyMap<string, ReadonlyMap<string, readonly OidcIdentityMappingRecord[]>>
>;

const EMPTY_MAPPINGS: readonly OidcIdentityMappingRecord[] = Object.freeze([]);

function fail(code: OidcIdentityMappingSourceErrorCode): OidcIdentityMappingSourceResult {
  return Object.freeze({
    ok: false as const,
    error: Object.freeze({ code, message: "OIDC identity mapping source is unavailable." })
  });
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function extractMappings(parsed: unknown): unknown[] | null {
  if (Array.isArray(parsed)) return parsed;
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
    const input = parsed as Record<string, unknown>;
    if (Object.keys(input).length === 1 && Array.isArray(input.mappings)) return input.mappings;
  }
  return null;
}

function activeAt(record: OidcIdentityMappingRecord, nowEpochSec: number): boolean {
  if (record.validFromEpochSec !== undefined && nowEpochSec < record.validFromEpochSec) return false;
  if (record.validUntilEpochSec !== undefined && nowEpochSec >= record.validUntilEpochSec) return false;
  return true;
}

function indexRecords(records: readonly OidcIdentityMappingRecord[]): MappingIndex | null {
  const issuers = new Map<string, Map<string, Map<string, readonly OidcIdentityMappingRecord[]>>>();
  for (const record of records) {
    let audiences = issuers.get(record.issuer);
    if (!audiences) {
      audiences = new Map();
      issuers.set(record.issuer, audiences);
    }
    let subjects = audiences.get(record.audience);
    if (!subjects) {
      subjects = new Map();
      audiences.set(record.audience, subjects);
    }
    if (subjects.has(record.subject)) return null;
    subjects.set(record.subject, Object.freeze([record]));
  }
  return issuers;
}

function createSource(
  records: readonly OidcIdentityMappingRecord[],
  nowEpochSec: () => number
): OidcIdentityMappingSource | null {
  const index = indexRecords(records);
  if (!index) return null;
  return Object.freeze({
    source: "CONFIGURED_MAPPING" as const,
    mappingCount: records.length,
    resolveCandidates(issuer: string, audience: string, subject: string) {
      const candidates = index.get(issuer)?.get(audience)?.get(subject) ?? EMPTY_MAPPINGS;
      const active = candidates.filter((record) => activeAt(record, nowEpochSec()));
      return active.length === candidates.length ? candidates : Object.freeze(active);
    }
  });
}

export function readConfiguredOidcIdentityMappingSource(
  env: OidcMappingEnvironment = process.env,
  options: ConfiguredSourceOptions = {}
): OidcIdentityMappingSourceResult {
  const rawValue = env[ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV];
  const raw = typeof rawValue === "string" && rawValue.trim() ? rawValue : undefined;
  if (!raw) return fail("MAPPING_CONFIG_REQUIRED");
  if (byteLength(raw) > MAX_OIDC_IDENTITY_MAPPING_CONFIG_BYTES) return fail("MAPPING_CONFIG_OVERSIZED");

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return fail("MAPPING_CONFIG_MALFORMED");
  }
  const rawMappings = extractMappings(parsed);
  if (!rawMappings) return fail("MAPPING_CONFIG_MALFORMED");
  if (rawMappings.length === 0) return fail("MAPPING_CONFIG_EMPTY");
  if (rawMappings.length > MAX_OIDC_IDENTITY_MAPPING_RECORDS) return fail("MAPPING_CONFIG_INVALID");

  const records: OidcIdentityMappingRecord[] = [];
  for (const candidate of rawMappings) {
    const record = normalizeConfiguredOidcMappingRecord(candidate);
    if (!record) return fail("MAPPING_CONFIG_INVALID");
    records.push(record);
  }
  const source = createSource(
    Object.freeze(records),
    options.nowEpochSec ?? (() => Math.floor(Date.now() / 1000))
  );
  return source ? Object.freeze({ ok: true as const, source }) : fail("MAPPING_CONFIG_INVALID");
}
