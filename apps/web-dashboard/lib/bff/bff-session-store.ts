/**
 * Server-side BFF session storage. Production-like deployments require Redis: there is no
 * Redis-to-memory fallback, missing/expired/revoked sessions fail closed, and Redis errors fail
 * closed. The in-memory store is allowed only under an explicit local/test mode
 * (ORDERPILOT_BFF_SESSION_STORE=memory on a non-production-like deployment).
 */
import { createClient } from "redis";
import type { OperatorSession } from "./bff-session.ts";
import { newOpaqueSessionId } from "./bff-session.ts";
import { sessionLifetimeWithinPolicy, sessionMaxAgeSecondsFromEnv } from "./bff-session-ttl-policy.ts";
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";
import { registeredBffRoutes } from "./bff-route-registry.ts";

export type StoredOperatorSession = OperatorSession & {
  sessionId: string;
  issuedAtEpochSec: number;
  sessionVersion: number;
  revoked: boolean;
};

export class SessionStoreUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SessionStoreUnavailableError";
  }
}

export class InvalidSessionAuthorityError extends Error {
  constructor() {
    super("Invalid BFF session authority.");
    this.name = "InvalidSessionAuthorityError";
  }
}

type MinimalRedisClient = {
  isOpen: boolean;
  connect(): Promise<unknown>;
  get(key: string): Promise<string | null>;
  setEx(key: string, ttlSeconds: number, value: string): Promise<unknown>;
  del(key: string): Promise<unknown>;
  on?(event: string, listener: (...args: unknown[]) => void): unknown;
};

const SESSION_KEY_PREFIX = "op:bff:session:";
const SESSION_ID_VALUE = /^[A-Za-z0-9_-]{43,128}$/;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PERMISSION_VALUE = /^[A-Z][A-Z0-9_]{1,64}$/;
const MAX_SESSION_PERMISSIONS = 64;
const ALLOWED_BFF_PERMISSIONS = new Set(registeredBffRoutes().map((rule) => rule.permission));

let redisClient: MinimalRedisClient | null = null;
let redisClientFactoryForTesting: ((url: string) => MinimalRedisClient) | null = null;
const memoryStore = new Map<string, StoredOperatorSession>();

export function setRedisClientFactoryForTesting(
  factory: ((url: string) => MinimalRedisClient) | null
): void {
  redisClientFactoryForTesting = factory;
  redisClient = null;
}

export function resetSessionStoreForTesting(): void {
  redisClientFactoryForTesting = null;
  redisClient = null;
  memoryStore.clear();
}

/** Test-only: mark an in-memory session expired without mutating production Redis semantics. */
export function expireOperatorSessionForTesting(sessionId: string): void {
  if (!memorySessionStoreAllowed()) {
    return;
  }
  const record = memoryStore.get(sessionId);
  if (!record) {
    return;
  }
  memoryStore.set(sessionId, {
    ...record,
    expiresAtEpochSec: Math.floor(Date.now() / 1000) - 1
  });
}

function sessionTtlSeconds(): number {
  return sessionMaxAgeSecondsFromEnv();
}

type SessionAuthorityCandidate = {
  sessionId?: unknown;
  tenantId?: unknown;
  actorId?: unknown;
  permissions?: unknown;
  issuedAtEpochSec?: unknown;
  expiresAtEpochSec?: unknown;
  sessionVersion?: unknown;
  revoked?: unknown;
};

function validateSessionAuthority(
  candidate: SessionAuthorityCandidate,
  options: { expectedSessionId?: string; nowEpochSec: number; requireActive: boolean }
): candidate is StoredOperatorSession {
  if (options.expectedSessionId !== undefined) {
    if (
      typeof candidate.sessionId !== "string" ||
      candidate.sessionId !== options.expectedSessionId ||
      !validSessionId(candidate.sessionId)
    ) {
      return false;
    }
  }
  if (typeof candidate.tenantId !== "string" || !UUID_VALUE.test(candidate.tenantId)) {
    return false;
  }
  if (typeof candidate.actorId !== "string" || !UUID_VALUE.test(candidate.actorId)) {
    return false;
  }
  if (!Array.isArray(candidate.permissions)) {
    return false;
  }
  const permissions = candidate.permissions;
  if (permissions.length === 0 || permissions.length > MAX_SESSION_PERMISSIONS) {
    return false;
  }
  const unique = new Set(permissions);
  if (
    unique.size !== permissions.length ||
    !permissions.every(
      (permission): permission is string =>
        typeof permission === "string" &&
        PERMISSION_VALUE.test(permission) &&
        ALLOWED_BFF_PERMISSIONS.has(permission)
    )
  ) {
    return false;
  }

  const { issuedAtEpochSec, expiresAtEpochSec, sessionVersion, revoked } = candidate;
  if (
    typeof issuedAtEpochSec !== "number" ||
    typeof expiresAtEpochSec !== "number" ||
    typeof sessionVersion !== "number" ||
    !Number.isSafeInteger(issuedAtEpochSec) ||
    !Number.isSafeInteger(expiresAtEpochSec) ||
    !Number.isSafeInteger(sessionVersion) ||
    issuedAtEpochSec <= 0 ||
    issuedAtEpochSec > options.nowEpochSec ||
    expiresAtEpochSec <= issuedAtEpochSec ||
    expiresAtEpochSec - issuedAtEpochSec > sessionTtlSeconds() ||
    sessionVersion !== 1 ||
    typeof revoked !== "boolean"
  ) {
    return false;
  }
  if (options.requireActive && (revoked || expiresAtEpochSec <= options.nowEpochSec)) {
    return false;
  }
  return true;
}

/** Memory storage is an explicit local/test mode only — never a production fallback. */
function memorySessionStoreAllowed(): boolean {
  return process.env.ORDERPILOT_BFF_SESSION_STORE === "memory" && !isProductionLikeDeployment();
}

function redisUrl(): string {
  return process.env.ORDERPILOT_BFF_REDIS_URL?.trim() || process.env.REDIS_URL?.trim() || "";
}

async function requireRedis(): Promise<MinimalRedisClient> {
  const url = redisUrl();
  if (!url) {
    throw new SessionStoreUnavailableError(
      "ORDERPILOT_BFF_REDIS_URL (or REDIS_URL) is required for BFF sessions"
    );
  }
  if (!redisClient) {
    redisClient = redisClientFactoryForTesting
      ? redisClientFactoryForTesting(url)
      : (createClient({ url }) as unknown as MinimalRedisClient);
    redisClient.on?.("error", () => {
      /* connection errors surface on command */
    });
  }
  if (!redisClient.isOpen) {
    try {
      await redisClient.connect();
    } catch {
      throw new SessionStoreUnavailableError("BFF Redis session backend is not reachable");
    }
  }
  return redisClient;
}

export async function persistOperatorSession(
  session: Omit<OperatorSession, "expiresAtEpochSec"> & { expiresAtEpochSec?: number }
): Promise<{ sessionId: string; record: StoredOperatorSession }> {
  const now = Math.floor(Date.now() / 1000);
  const authority = {
    tenantId: session.tenantId,
    actorId: session.actorId,
    permissions: [...session.permissions],
    issuedAtEpochSec: now,
    expiresAtEpochSec: session.expiresAtEpochSec ?? now + sessionTtlSeconds(),
    sessionVersion: 1,
    revoked: false
  };
  if (!validateSessionAuthority(authority, { nowEpochSec: now, requireActive: true })) {
    throw new InvalidSessionAuthorityError();
  }
  const sessionId = newOpaqueSessionId();
  const record: StoredOperatorSession = {
    sessionId,
    tenantId: authority.tenantId,
    actorId: authority.actorId,
    permissions: authority.permissions,
    issuedAtEpochSec: authority.issuedAtEpochSec,
    expiresAtEpochSec: authority.expiresAtEpochSec,
    sessionVersion: authority.sessionVersion,
    revoked: authority.revoked
  };
  if (memorySessionStoreAllowed()) {
    memoryStore.set(sessionId, record);
    return { sessionId, record };
  }
  const redis = await requireRedis();
  const ttl = Math.max(1, record.expiresAtEpochSec - now);
  try {
    await redis.setEx(`${SESSION_KEY_PREFIX}${sessionId}`, ttl, JSON.stringify(record));
  } catch {
    throw new SessionStoreUnavailableError("BFF Redis session write failed");
  }
  return { sessionId, record };
}

function validSessionId(sessionId: string | undefined): sessionId is string {
  return Boolean(sessionId && SESSION_ID_VALUE.test(sessionId));
}

function parseStoredRecord(raw: string | null, expectedSessionId: string): StoredOperatorSession | null {
  if (!raw || raw.length > 8192) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") {
    return null;
  }
  const record = parsed as Partial<StoredOperatorSession>;
  if (
    !validateSessionAuthority(record, {
      expectedSessionId,
      nowEpochSec: Math.floor(Date.now() / 1000),
      requireActive: true
    })
  ) {
    return null;
  }
  return record;
}

function liveOrNull(record: StoredOperatorSession | null): StoredOperatorSession | null {
  if (!record) {
    return null;
  }
  if (record.revoked || record.expiresAtEpochSec <= Math.floor(Date.now() / 1000)) {
    return null;
  }
  return record;
}

/** Missing, expired, revoked sessions and Redis errors all fail closed (null). */
export async function loadOperatorSession(
  sessionId: string | undefined
): Promise<StoredOperatorSession | null> {
  if (!validSessionId(sessionId)) {
    return null;
  }
  if (memorySessionStoreAllowed()) {
    return liveOrNull(parseStoredRecord(JSON.stringify(memoryStore.get(sessionId) ?? null), sessionId));
  }
  try {
    const redis = await requireRedis();
    const raw = await redis.get(`${SESSION_KEY_PREFIX}${sessionId}`);
    return liveOrNull(parseStoredRecord(raw, sessionId));
  } catch {
    return null;
  }
}

/** Deletes the server-side session. Throws SessionStoreUnavailableError when Redis fails. */
export async function revokeOperatorSession(sessionId: string | undefined): Promise<void> {
  if (!validSessionId(sessionId)) {
    return;
  }
  if (memorySessionStoreAllowed()) {
    memoryStore.delete(sessionId);
    return;
  }
  const redis = await requireRedis();
  try {
    await redis.del(`${SESSION_KEY_PREFIX}${sessionId}`);
  } catch {
    throw new SessionStoreUnavailableError("BFF Redis session revocation failed");
  }
}

/** Config validation: production-like sessions require a reachable Redis backend. */
export async function requireRedisSessionBackend(): Promise<string | null> {
  if (memorySessionStoreAllowed()) {
    return null;
  }
  try {
    await requireRedis();
    return null;
  } catch (error) {
    return error instanceof SessionStoreUnavailableError
      ? error.message
      : "BFF Redis session backend is not reachable";
  }
}
