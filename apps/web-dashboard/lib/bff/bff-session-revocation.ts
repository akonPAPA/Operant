import { randomBytes } from "node:crypto";

const revokedSessionIds = new Set<string>();

export function newSessionId(): string {
  return randomBytes(16).toString("hex");
}

export function revokeSession(sessionId: string): void {
  if (sessionId) {
    revokedSessionIds.add(sessionId);
  }
}

export function isSessionRevoked(sessionId: string | undefined): boolean {
  return sessionId ? revokedSessionIds.has(sessionId) : true;
}

/** Test-only reset. */
export function clearRevokedSessionsForTests(): void {
  revokedSessionIds.clear();
}
