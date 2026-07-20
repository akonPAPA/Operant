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
import {
  BffRedisConfigurationError,
  bffRedisConnectionOptions,
  type BffRedisConnectionOptions,
  type MinimalBffRedisClient
} from "./bff-redis-connection.ts";

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

type MinimalRedisClient = MinimalBffRedisClient & {
  get(key: string): Promise<string | null>;
  setEx(key: string, ttlSeconds: number, value: string): Promise<unknown>;
  del(key: string): Promise<unknown>;
};

const SESSION_KEY_PREFIX = "op:bff:session:";
const SESSION_ID_VALUE = /^[A-Za-z0-9_-]{43,128}$/;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PERMISSION_VALUE = /^[A-Z][A-Z0-9_]{1,64}$/;
const MAX_SESSION_PERMISSIONS = 64;
const ALLOWED_BFF_PERMISSIONS = new Set(registeredBffRoutes().map((rule) => rule.permission));

let redisClient: MinimalRedisClient | null = null;
let redisClientFactoryForTesting: ((options: BffRedisConnectionOptions) => MinimalRedisClient) | null = null;
/** Coalesces concurrent connect attempts into one in-flight promise (F04: no connect stampede). */
let redisConnectInFlight: Promise<void> | null = null;
const memoryStore = new Map<string, StoredOperatorSession>();

export function setRedisClientFactoryForTesting(
  factory: ((options: BffRedisConnectionOptions) => MinimalRedisClient) | null
): void {
  redisClientFactoryForTesting = factory;
  redisClient = null;
  redisConnectInFlight = null;
}

export function resetSessionStoreForTesting(): void {
  redisClientFactoryForTesting = null;
  redisClient = null;
  redisConnectInFlight = null;
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

async function requireRedis(): Promise<MinimalRedisClient> {
  let options: BffRedisConnectionOptions;
  try {
    options = bffRedisConnectionOptions();
  } catch (error) {
    if (error instanceof BffRedisConfigurationError) {
      throw new SessionStoreUnavailableError(error.message);
    }
    throw new SessionStoreUnavailableError("BFF Redis host, port, and password are required.");
  }
  if (!redisClient) {
    redisClient = redisClientFactoryForTesting
      ? redisClientFactoryForTesting(options)
      : (createClient({
          ...options
        }) as unknown as MinimalRedisClient);
    redisClient.on?.("error", () => {
      /* connection errors surface on command */
    });
  }
  const client = redisClient;
  if (!client.isOpen) {
    // Only one connect is ever in flight; concurrent callers await the same attempt.
    if (!redisConnectInFlight) {
      redisConnectInFlight = Promise.resolve(client.connect())
        .then(() => undefined)
        .finally(() => {
          redisConnectInFlight = null;
        });
    }
    try {
      await redisConnectInFlight;
    } catch {
      throw new SessionStoreUnavailableError("BFF Redis session backend is not reachable");
    }
  }
  return client;
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

/**
 * Three-state session resolution for callers that must distinguish store outages from
 * missing/inactive sessions (e.g. UI capability projection → 503 vs 401).
 *
 * Does not duplicate Redis key format, TTL, revocation, or authority validation — reuses the
 * same parse/live path as {@link loadOperatorSession}.
 */
export type OperatorSessionLoadResult =
  | { status: "ACTIVE"; session: StoredOperatorSession }
  | { status: "MISSING_OR_INACTIVE" }
  | { status: "STORE_UNAVAILABLE" };

export async function loadOperatorSessionResult(
  sessionId: string | undefined
): Promise<OperatorSessionLoadResult> {
  if (!validSessionId(sessionId)) {
    return { status: "MISSING_OR_INACTIVE" };
  }
  if (memorySessionStoreAllowed()) {
    const session = liveOrNull(
      parseStoredRecord(JSON.stringify(memoryStore.get(sessionId) ?? null), sessionId)
    );
    return session ? { status: "ACTIVE", session } : { status: "MISSING_OR_INACTIVE" };
  }
  try {
    const redis = await requireRedis();
    let raw: string | null;
    try {
      raw = await redis.get(`${SESSION_KEY_PREFIX}${sessionId}`);
    } catch {
      return { status: "STORE_UNAVAILABLE" };
    }
    const session = liveOrNull(parseStoredRecord(raw, sessionId));
    return session ? { status: "ACTIVE", session } : { status: "MISSING_OR_INACTIVE" };
  } catch (error) {
    if (error instanceof SessionStoreUnavailableError || error instanceof BffRedisConfigurationError) {
      return { status: "STORE_UNAVAILABLE" };
    }
    // Unknown internal failure — fail closed as store unavailable (no raw detail).
    return { status: "STORE_UNAVAILABLE" };
  }
}

/**
 * Missing, expired, revoked sessions and Redis errors all fail closed (null).
 * Ordinary BFF route authorization must keep this fail-closed null semantics.
 */
export async function loadOperatorSession(
  sessionId: string | undefined
): Promise<StoredOperatorSession | null> {
  const result = await loadOperatorSessionResult(sessionId);
  return result.status === "ACTIVE" ? result.session : null;
}

/** Local-test memory sessions only: replace permission grants without rotating session id. */
export async function replaceOperatorSessionPermissions(
  sessionId: string,
  permissions: string[]
): Promise<boolean> {
  if (!validSessionId(sessionId) || !memorySessionStoreAllowed()) {
    return false;
  }
  const existing = memoryStore.get(sessionId);
  if (!existing || existing.revoked) {
    return false;
  }
  const now = Math.floor(Date.now() / 1000);
  const candidate = {
    ...existing,
    permissions: [...permissions]
  };
  if (
    !validateSessionAuthority(candidate, {
      expectedSessionId: sessionId,
      nowEpochSec: now,
      requireActive: true
    })
  ) {
    return false;
  }
  memoryStore.set(sessionId, candidate);
  return true;
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
