import { randomBytes } from "node:crypto";
import { sessionMaxAgeSecondsFromEnv } from "./bff-session-ttl-policy.ts";

export type OperatorSession = {
  tenantId: string;
  actorId: string;
  permissions: string[];
  expiresAtEpochSec: number;
};

function base64UrlEncode(buffer: Buffer): string {
  return buffer.toString("base64url");
}

export function newCsrfToken(): string {
  return base64UrlEncode(randomBytes(24));
}

export function sessionMaxAgeSeconds(): number {
  return sessionMaxAgeSecondsFromEnv();
}

export function newOpaqueSessionId(): string {
  return base64UrlEncode(randomBytes(32));
}
