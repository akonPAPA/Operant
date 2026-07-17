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
}
