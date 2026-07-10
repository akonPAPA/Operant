import { createHmac, randomBytes } from "node:crypto";

const TENANT_HEADER = "X-Tenant-Id";
const ACTOR_HEADER = "X-OrderPilot-Actor-Id";
const PERMISSIONS_HEADER = "X-OrderPilot-Permissions";
const TIMESTAMP_HEADER = "X-OrderPilot-Gateway-Timestamp";
const SIGNATURE_HEADER = "X-OrderPilot-Gateway-Signature";
const NONCE_HEADER = "X-OrderPilot-Gateway-Nonce";

export function gatewayCanonicalString(
  method: string,
  requestUri: string,
  tenantId: string,
  actorId: string,
  permissions: string,
  timestampEpoch: number,
  nonce: string
): string {
  return `${method.toUpperCase()}\n${requestUri}\n${tenantId.trim()}\n${actorId.trim()}\n${permissions.trim()}\n${timestampEpoch}\n${nonce.trim()}`;
}

export function signGatewayHeaders(input: {
  method: string;
  requestUri: string;
  tenantId: string;
  actorId: string;
  permissions: string[];
  sharedSecret: string;
}): Record<string, string> {
  const timestampEpoch = Math.floor(Date.now() / 1000);
  const nonce = randomBytes(16).toString("hex");
  const permissions = input.permissions.join(",");
  const canonical = gatewayCanonicalString(
    input.method,
    input.requestUri,
    input.tenantId,
    input.actorId,
    permissions,
    timestampEpoch,
    nonce
  );
  const signature = createHmac("sha256", input.sharedSecret).update(canonical, "utf8").digest("hex");
  return {
    [TENANT_HEADER]: input.tenantId,
    [ACTOR_HEADER]: input.actorId,
    [PERMISSIONS_HEADER]: permissions,
    [TIMESTAMP_HEADER]: String(timestampEpoch),
    [NONCE_HEADER]: nonce,
    [SIGNATURE_HEADER]: signature
  };
}
