import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD = "ORDERPILOT_OIDC_BINDING_TEST_CHILD";
if (process.env[CHILD] !== "1") {
  test("OIDC browser-binding contract passes under server-only condition", () => {
    const result = spawnSync(
      process.execPath,
      ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)],
      { cwd: process.cwd(), env: { ...process.env, [CHILD]: "1" }, encoding: "utf8" }
    );
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const binding = await import("../lib/bff/bff-oidc-browser-binding.ts");
  const store = await import("../lib/bff/bff-oidc-transaction-store.ts");

  test("callback binding is browser-specific and authorization state is single-use", async () => {
    const priorStore = process.env.ORDERPILOT_BFF_SESSION_STORE;
    const priorProfile = process.env.ORDERPILOT_DEPLOY_PROFILE;
    const priorNode = process.env.NODE_ENV;
    process.env.ORDERPILOT_BFF_SESSION_STORE = "memory";
    process.env.ORDERPILOT_DEPLOY_PROFILE = "local-test";
    process.env.NODE_ENV = "test";
    store.resetOidcTransactionStoreForTesting();
    try {
      const browserA = store.newOidcTransactionSecret();
      const browserB = store.newOidcTransactionSecret();
      const headers = new Headers();
      binding.issueOidcBindingCookie(headers, browserA);
      const cookiePair = headers.getSetCookie()[0].split(";")[0];
      assert.equal(
        binding.readOidcBindingCookie(new Request("http://127.0.0.1/callback", { headers: { cookie: cookiePair } })),
        browserA
      );
      assert.equal(
        binding.sameOidcBindingHash(binding.oidcBindingHash(browserA), binding.oidcBindingHash(browserB)),
        false
      );

      const now = Math.floor(Date.now() / 1000);
      const state = store.newOidcTransactionSecret();
      await store.persistOidcAuthorizationTransaction({
        state,
        nonce: store.newOidcTransactionSecret(),
        pkceVerifier: store.newOidcTransactionSecret(),
        browserBindingHash: binding.oidcBindingHash(browserA),
        redirectUri: "http://127.0.0.1/api/auth/oidc/callback",
        issuer: "https://idp.example.test",
        audience: "operant-dashboard-client",
        createdAtEpochSec: now,
        expiresAtEpochSec: now + 300
      });
      const consumed = await store.consumeOidcAuthorizationTransaction(state);
      assert.ok(consumed);
      assert.equal(consumed.browserBindingHash, binding.oidcBindingHash(browserA));
      assert.equal(await store.consumeOidcAuthorizationTransaction(state), null);
    } finally {
      if (priorStore === undefined) delete process.env.ORDERPILOT_BFF_SESSION_STORE;
      else process.env.ORDERPILOT_BFF_SESSION_STORE = priorStore;
      if (priorProfile === undefined) delete process.env.ORDERPILOT_DEPLOY_PROFILE;
      else process.env.ORDERPILOT_DEPLOY_PROFILE = priorProfile;
      if (priorNode === undefined) delete process.env.NODE_ENV;
      else process.env.NODE_ENV = priorNode;
      store.resetOidcTransactionStoreForTesting();
    }
  });

  test("OIDC Redis transaction store uses structured Redis options and preserves reserved password", async () => {
    const prior = {};
    for (const key of [
      "NODE_ENV",
      "ORDERPILOT_DEPLOY_PROFILE",
      "ORDERPILOT_BFF_SESSION_STORE",
      "ORDERPILOT_BFF_REDIS_HOST",
      "ORDERPILOT_BFF_REDIS_PORT",
      "ORDERPILOT_BFF_REDIS_PASSWORD",
      "ORDERPILOT_BFF_REDIS_URL",
      "REDIS_URL"
    ]) {
      prior[key] = process.env[key];
      delete process.env[key];
    }
    process.env.NODE_ENV = "test";
    process.env.ORDERPILOT_DEPLOY_PROFILE = "local-test";
    process.env.ORDERPILOT_BFF_SESSION_STORE = "redis";
    process.env.ORDERPILOT_BFF_REDIS_HOST = "redis";
    process.env.ORDERPILOT_BFF_REDIS_PORT = "6379";
    process.env.ORDERPILOT_BFF_REDIS_PASSWORD = "p/a?s#s@w:ord%25";
    store.resetOidcTransactionStoreForTesting();
    let capturedOptions;
    const records = new Map();
    try {
      store.setOidcTransactionRedisClientFactoryForTesting((options) => {
        capturedOptions = options;
        return {
          isOpen: true,
          connect: async () => {},
          set: async (key, value) => {
            records.set(key, value);
            return "OK";
          },
          getDel: async (key) => {
            const value = records.get(key) ?? null;
            records.delete(key);
            return value;
          },
          on: () => {}
        };
      });
      const now = Math.floor(Date.now() / 1000);
      const state = store.newOidcTransactionSecret();
      await store.persistOidcAuthorizationTransaction({
        state,
        nonce: store.newOidcTransactionSecret(),
        pkceVerifier: store.newOidcTransactionSecret(),
        browserBindingHash: binding.oidcBindingHash(store.newOidcTransactionSecret()),
        redirectUri: "http://127.0.0.1/api/auth/oidc/callback",
        issuer: "https://idp.example.test",
        audience: "operant-dashboard-client",
        createdAtEpochSec: now,
        expiresAtEpochSec: now + 300
      });
      assert.equal(capturedOptions.password, "p/a?s#s@w:ord%25");
      assert.deepEqual(capturedOptions.socket, {
        host: "redis",
        port: 6379,
        reconnectStrategy: false,
        connectTimeout: 5000
      });
      assert.ok(await store.consumeOidcAuthorizationTransaction(state));
      assert.equal(await store.consumeOidcAuthorizationTransaction(state), null);
    } finally {
      for (const key of Object.keys(prior)) {
        if (prior[key] === undefined) delete process.env[key];
        else process.env[key] = prior[key];
      }
      store.resetOidcTransactionStoreForTesting();
    }
  });
}
