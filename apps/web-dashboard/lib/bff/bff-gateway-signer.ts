import { createHmac, createHash, randomBytes } from "node:crypto";
import { decodeGatewaySharedSecret } from "./bff-gateway-key.ts";

const TENANT_HEADER = "X-Tenant-Id";
const ACTOR_HEADER = "X-OrderPilot-Actor-Id";
const PERMISSIONS_HEADER = "X-OrderPilot-Permissions";
const TIMESTAMP_HEADER = "X-OrderPilot-Gateway-Timestamp";
const SIGNATURE_HEADER = "X-OrderPilot-Gateway-Signature";
const NONCE_HEADER = "X-OrderPilot-Gateway-Nonce";
export const SIGNATURE_VERSION_HEADER = "X-OrderPilot-Signature-Version";
export const CONTENT_SHA256_HEADER = "X-OrderPilot-Content-SHA256";
export const GATEWAY_SIGNATURE_VERSION = "2";
export const GATEWAY_PROTOCOL_MARKER = "ORDERPILOT_GATEWAY_V2";
export const EMPTY_BODY_SHA256_HEX =
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

function rejectCrLf(value: string, field: string): string {
  if (value.includes("\n") || value.includes("\r")) {
    throw new Error(`${field} must not contain CR/LF`);
  }
  return value;
}

export function sha256Hex(bytes: Buffer | Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * Byte-identical canonical string for Core GatewayV2Canonical.
 * Field order is fixed; permissions must already be the single route permission.
 */
export function gatewayCanonicalStringV2(input: {
  method: string;
  path: string;
  rawQuery: string;
  contentType: string;
  bodySha256Hex: string;
  tenantId: string;
  actorId: string;
  permissions: string;
  timestampEpoch: number;
  nonce: string;
}): string {
  return [
    GATEWAY_PROTOCOL_MARKER,
    rejectCrLf(input.method.toUpperCase(), "method"),
    rejectCrLf(input.path, "path"),
    rejectCrLf(input.rawQuery, "query"),
    rejectCrLf(input.contentType, "contentType"),
    rejectCrLf(input.bodySha256Hex.toLowerCase(), "bodySha256"),
    rejectCrLf(input.tenantId.trim(), "tenantId"),
    rejectCrLf(input.actorId.trim(), "actorId"),
    rejectCrLf(input.permissions.trim(), "permissions"),
    String(input.timestampEpoch),
    rejectCrLf(input.nonce.trim(), "nonce")
  ].join("\n");
}

/** @deprecated Use gatewayCanonicalStringV2 — kept only for migration detection in tests. */
export function gatewayCanonicalString(
  method: string,
  requestUri: string,
  tenantId: string,
  actorId: string,
  permissions: string,
  timestampEpoch: number,
  nonce: string
): string {
  return gatewayCanonicalStringV2({
    method,
    path: requestUri,
    rawQuery: "",
    contentType: "",
    bodySha256Hex: EMPTY_BODY_SHA256_HEX,
    tenantId,
    actorId,
    permissions,
    timestampEpoch,
    nonce
  });
}

export function signGatewayHeaders(input: {
  method: string;
  path: string;
  /** Exact raw query string without leading '?', matching Core getQueryString(). */
  rawQuery: string;
  /** Normalized content type, or empty when no body. */
  contentType: string;
  /** Exact forwarded body bytes (JSON.stringify output or empty). */
  bodyBytes: Buffer | Uint8Array;
  tenantId: string;
  actorId: string;
  /** Exactly one server-derived route permission. */
  permissions: string[];
  /** Hex-configured shared secret (64 chars); decoded before HMAC. */
  sharedSecret: string;
  timestampEpochForTesting?: number;
  nonceForTesting?: string;
}): Record<string, string> {
  const decoded = decodeGatewaySharedSecret(input.sharedSecret);
  if (!decoded.ok) {
    throw new Error("Gateway shared secret is not configured");
  }
  if (input.permissions.length !== 1) {
    throw new Error("Gateway signature requires exactly one route permission");
  }
  const timestampEpoch = input.timestampEpochForTesting ?? Math.floor(Date.now() / 1000);
  const nonce = input.nonceForTesting ?? randomBytes(16).toString("hex");
  const permissions = input.permissions[0];
  const bodySha256Hex = sha256Hex(input.bodyBytes);
  const contentType =
    input.bodyBytes.byteLength === 0 ? "" : rejectCrLf(input.contentType, "contentType");
  const canonical = gatewayCanonicalStringV2({
    method: input.method,
    path: input.path,
    rawQuery: input.rawQuery,
    contentType,
    bodySha256Hex,
    tenantId: input.tenantId,
    actorId: input.actorId,
    permissions,
    timestampEpoch,
    nonce
  });
  const signature = createHmac("sha256", decoded.key).update(canonical, "utf8").digest("hex");
  return {
    [TENANT_HEADER]: input.tenantId,
    [ACTOR_HEADER]: input.actorId,
    [PERMISSIONS_HEADER]: permissions,
    [TIMESTAMP_HEADER]: String(timestampEpoch),
    [NONCE_HEADER]: nonce,
    [SIGNATURE_VERSION_HEADER]: GATEWAY_SIGNATURE_VERSION,
    [CONTENT_SHA256_HEADER]: bodySha256Hex,
    [SIGNATURE_HEADER]: signature
  };
}
