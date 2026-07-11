import { createHmac, timingSafeEqual } from "node:crypto";

export type StatelessOperatorSession = {
  tenantId: string;
  actorId: string;
  permissions: string[];
  expiresAtEpochSec: number;
};

function base64UrlEncode(buffer: Buffer): string {
  return buffer.toString("base64url");
}

function base64UrlDecode(value: string): Buffer {
  return Buffer.from(value, "base64url");
}

/** Test-only legacy stateless session token helpers (not used in production BFF). */
export function createSessionToken(session: StatelessOperatorSession, secret: string): string {
  if (!secret) {
    throw new Error("Session secret is not configured");
  }
  const payload = base64UrlEncode(Buffer.from(JSON.stringify(session), "utf8"));
  const sig = createHmac("sha256", secret).update(payload).digest("base64url");
  return `${payload}.${sig}`;
}

export function parseSessionToken(token: string | undefined, secret: string): StatelessOperatorSession | null {
  if (!token || !secret) {
    return null;
  }
  const parts = token.split(".");
  if (parts.length !== 2) {
    return null;
  }
  const [payload, sig] = parts;
  const expected = createHmac("sha256", secret).update(payload).digest("base64url");
  try {
    const a = Buffer.from(sig, "utf8");
    const b = Buffer.from(expected, "utf8");
    if (a.length !== b.length || !timingSafeEqual(a, b)) {
      return null;
    }
  } catch {
    return null;
  }
  try {
    const session = JSON.parse(base64UrlDecode(payload).toString("utf8")) as StatelessOperatorSession;
    if (
      typeof session.tenantId !== "string"
      || typeof session.actorId !== "string"
      || !Array.isArray(session.permissions)
      || typeof session.expiresAtEpochSec !== "number"
    ) {
      return null;
    }
    if (session.expiresAtEpochSec <= Math.floor(Date.now() / 1000)) {
      return null;
    }
    return session;
  } catch {
    return null;
  }
}
