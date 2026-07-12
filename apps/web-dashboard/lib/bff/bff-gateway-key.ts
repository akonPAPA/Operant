/**
 * Cryptographic contract for ORDERPILOT_GATEWAY_SHARED_SECRET /
 * ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET.
 *
 * Value must be exactly 64 hex chars (32 decoded bytes). HMAC uses the decoded Buffer, never the
 * ASCII hex text. Invalid values fail closed; the secret is never placed in thrown messages.
 */
const HEX_64 = /^[0-9a-fA-F]{64}$/;

export type GatewaySecretDecode =
  | { ok: true; key: Buffer }
  | { ok: false; reason: "missing" | "invalid" };

function isWeakOrPlaceholderHex(hex: string): boolean {
  const lower = hex.toLowerCase();
  const distinct = new Set(lower).size;
  if (distinct <= 2) {
    return true;
  }
  for (let block = 1; block <= 4; block += 1) {
    if (lower.length % block !== 0) {
      continue;
    }
    const unit = lower.slice(0, block);
    if (unit.repeat(lower.length / block) === lower) {
      return true;
    }
  }
  return (
    lower.includes("deadbeef") ||
    lower.includes("cafebabe") ||
    lower.includes("change") ||
    lower.includes("secret") ||
    lower.includes("password") ||
    lower.includes("placeholder")
  );
}

export function decodeGatewaySharedSecret(configured: string | undefined | null): GatewaySecretDecode {
  if (configured == null) {
    return { ok: false, reason: "missing" };
  }
  if (configured !== configured.trim()) {
    return { ok: false, reason: "invalid" };
  }
  if (!configured) {
    return { ok: false, reason: "missing" };
  }
  if (!HEX_64.test(configured) || isWeakOrPlaceholderHex(configured)) {
    return { ok: false, reason: "invalid" };
  }
  return { ok: true, key: Buffer.from(configured, "hex") };
}

/** Raw env read — prefers ORDERPILOT_GATEWAY_SHARED_SECRET, falls back to legacy header-auth name. */
export function readGatewaySharedSecretEnv(): string {
  const primary = process.env.ORDERPILOT_GATEWAY_SHARED_SECRET;
  if (primary != null && primary.length > 0) {
    return primary;
  }
  return process.env.ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET ?? "";
}
