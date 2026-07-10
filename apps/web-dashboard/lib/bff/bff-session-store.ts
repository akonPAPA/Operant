/**
 * Server-side BFF session storage. Production-like deployments require Redis: there is no
 * Redis-to-memory fallback, missing/expired/revoked sessions fail closed, and Redis errors fail
 * closed. The in-memory store is allowed only under an explicit local/test mode
 * (ORDERPILOT_BFF_SESSION_STORE=memory on a non-production-like deployment).
 */
import { createClient } from "redis";
import { randomBytes } from "node:crypto";
import type { OperatorSession } from "./bff-session.ts";
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";

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

type MinimalRedisClient = {
  isOpen: boolean;
  connect(): Promise<unknown>;
  get(key: string): Promise<string | null>;
  setEx(key: string, ttlSeconds: number, value: string): Promise<unknown>;
  del(key: string): Promise<unknown>;
  on?(event: string, listener: (...args: unknown[]) => void): unknown;
};

const SESSION_KEY_PREFIX = "op:bff:session:";
const DEFAULT_TTL_SECONDS = 28_800;

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

function sessionTtlSeconds(): number {
  const raw = process.env.ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS ?? "28800";
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_TTL_SECONDS;
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

export function newOpaqueSessionId(): string {
  return randomBytes(32).toString("base64url");
}

export async function persistOperatorSession(
  session: Omit<OperatorSession, "expiresAtEpochSec"> & { expiresAtEpochSec?: number }
): Promise<{ sessionId: string; record: StoredOperatorSession }> {
  const sessionId = newOpaqueSessionId();
  const now = Math.floor(Date.now() / 1000);
  const record: StoredOperatorSession = {
    sessionId,
    tenantId: session.tenantId,
    actorId: session.actorId,
    permissions: [...session.permissions],
    issuedAtEpochSec: now,
    expiresAtEpochSec: session.expiresAtEpochSec ?? now + sessionTtlSeconds(),
    sessionVersion: 1,
    revoked: false
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
  if (!sessionId?.trim()) {
    return null;
  }
  if (memorySessionStoreAllowed()) {
    return liveOrNull(memoryStore.get(sessionId) ?? null);
  }
  try {
    const redis = await requireRedis();
    const raw = await redis.get(`${SESSION_KEY_PREFIX}${sessionId}`);
    if (!raw) {
      return null;
    }
    return liveOrNull(JSON.parse(raw) as StoredOperatorSession);
  } catch {
    return null;
  }
}

/** Deletes the server-side session. Throws SessionStoreUnavailableError when Redis fails. */
export async function revokeOperatorSession(sessionId: string | undefined): Promise<void> {
  if (!sessionId?.trim()) {
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
