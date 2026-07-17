import "server-only";

import { createClient } from "redis";
import { randomBytes } from "node:crypto";
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";

export type OidcAuthorizationTransaction = Readonly<{
  state: string;
  nonce: string;
  pkceVerifier: string;
  browserBindingHash: string;
  redirectUri: string;
  issuer: string;
  audience: string;
  createdAtEpochSec: number;
  expiresAtEpochSec: number;
}>;

type MinimalRedisClient = {
  isOpen: boolean;
  connect(): Promise<unknown>;
  set(key: string, value: string, options: { EX: number; NX: true }): Promise<string | null>;
  getDel(key: string): Promise<string | null>;
  on?(event: string, listener: (...args: unknown[]) => void): unknown;
};

const TRANSACTION_KEY_PREFIX = "op:oidc:tx:";
const STATE_VALUE = /^[A-Za-z0-9_-]{32,256}$/;
const BINDING_HASH_VALUE = /^[A-Za-z0-9_-]{43}$/;
const TEXT_VALUE = /^[^\x00-\x1f\x7f]{1,1024}$/;
const MAX_TRANSACTION_AGE_SECONDS = 600;
const memoryStore = new Map<string, OidcAuthorizationTransaction>();
let redisClient: MinimalRedisClient | null = null;
let redisClientFactoryForTesting: ((url: string) => MinimalRedisClient) | null = null;
let redisConnectInFlight: Promise<void> | null = null;

export class OidcTransactionStoreUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OidcTransactionStoreUnavailableError";
  }
}

export function setOidcTransactionRedisClientFactoryForTesting(
  factory: ((url: string) => MinimalRedisClient) | null
): void {
  redisClientFactoryForTesting = factory;
  redisClient = null;
  redisConnectInFlight = null;
}

export function resetOidcTransactionStoreForTesting(): void {
  redisClientFactoryForTesting = null;
  redisClient = null;
  redisConnectInFlight = null;
  memoryStore.clear();
}

function memoryTransactionStoreAllowed(): boolean {
  return process.env.ORDERPILOT_BFF_SESSION_STORE === "memory" && !isProductionLikeDeployment();
}

function redisUrl(): string {
  return process.env.ORDERPILOT_BFF_REDIS_URL?.trim() || process.env.REDIS_URL?.trim() || "";
}

async function requireRedis(): Promise<MinimalRedisClient> {
  const url = redisUrl();
  if (!url) {
    throw new OidcTransactionStoreUnavailableError(
      "ORDERPILOT_BFF_REDIS_URL (or REDIS_URL) is required for OIDC transactions"
    );
  }
  if (!redisClient) {
    redisClient = redisClientFactoryForTesting
      ? redisClientFactoryForTesting(url)
      : (createClient({
          url,
          socket: { reconnectStrategy: false, connectTimeout: 5000 }
        }) as unknown as MinimalRedisClient);
    redisClient.on?.("error", () => undefined);
  }
  const client = redisClient;
  if (!client.isOpen) {
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
      throw new OidcTransactionStoreUnavailableError("OIDC Redis transaction backend is not reachable");
    }
  }
  return client;
}

function validTransaction(tx: OidcAuthorizationTransaction, nowEpochSec: number): boolean {
  return (
    STATE_VALUE.test(tx.state) &&
    STATE_VALUE.test(tx.nonce) &&
    STATE_VALUE.test(tx.pkceVerifier) &&
    BINDING_HASH_VALUE.test(tx.browserBindingHash) &&
    TEXT_VALUE.test(tx.redirectUri) &&
    TEXT_VALUE.test(tx.issuer) &&
    TEXT_VALUE.test(tx.audience) &&
    Number.isSafeInteger(tx.createdAtEpochSec) &&
    Number.isSafeInteger(tx.expiresAtEpochSec) &&
    tx.createdAtEpochSec > 0 &&
    tx.createdAtEpochSec <= nowEpochSec &&
    tx.expiresAtEpochSec > nowEpochSec &&
    tx.expiresAtEpochSec - tx.createdAtEpochSec <= MAX_TRANSACTION_AGE_SECONDS
  );
}

function parseTransaction(raw: string | null, expectedState: string): OidcAuthorizationTransaction | null {
  if (!raw || raw.length > 8192 || !STATE_VALUE.test(expectedState)) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;
  const tx = parsed as OidcAuthorizationTransaction;
  if (tx.state !== expectedState || !validTransaction(tx, Math.floor(Date.now() / 1000))) return null;
  return Object.freeze({ ...tx });
}

export function newOidcTransactionSecret(): string {
  return randomBytes(32).toString("base64url");
}

export async function persistOidcAuthorizationTransaction(
  transaction: OidcAuthorizationTransaction
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  if (!validTransaction(transaction, now)) {
    throw new OidcTransactionStoreUnavailableError("OIDC authorization transaction is invalid");
  }
  const ttl = Math.max(1, transaction.expiresAtEpochSec - now);
  if (memoryTransactionStoreAllowed()) {
    if (memoryStore.has(transaction.state)) {
      throw new OidcTransactionStoreUnavailableError("OIDC authorization transaction could not be stored");
    }
    memoryStore.set(transaction.state, Object.freeze({ ...transaction }));
    return;
  }
  const redis = await requireRedis();
  const stored = await redis.set(
    `${TRANSACTION_KEY_PREFIX}${transaction.state}`,
    JSON.stringify(transaction),
    { EX: ttl, NX: true }
  );
  if (stored !== "OK") {
    throw new OidcTransactionStoreUnavailableError("OIDC authorization transaction could not be stored");
  }
}

export async function consumeOidcAuthorizationTransaction(
  state: string | undefined
): Promise<OidcAuthorizationTransaction | null> {
  if (!state || !STATE_VALUE.test(state)) return null;
  if (memoryTransactionStoreAllowed()) {
    const tx = memoryStore.get(state) ?? null;
    memoryStore.delete(state);
    return parseTransaction(tx ? JSON.stringify(tx) : null, state);
  }
  try {
    const redis = await requireRedis();
    return parseTransaction(await redis.getDel(`${TRANSACTION_KEY_PREFIX}${state}`), state);
  } catch {
    return null;
  }
}
