import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { bffServerReadWithSessionCookie } from "../lib/bff/bff-server-read.ts";
import {
  persistOperatorSession,
  resetSessionStoreForTesting
} from "../lib/bff/bff-session-store.ts";

const testDir = dirname(fileURLToPath(import.meta.url));
const root = join(testDir, "..");

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_BFF_SESSION_SECRET",
  "ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET",
  "CORE_API_BASE_URL",
  "ORDERPILOT_BFF_REDIS_URL",
  "REDIS_URL"
];

const BFF_ENV = {
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_BFF_SESSION_SECRET: "x".repeat(48),
  ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET: "gateway-test-only-secret",
  CORE_API_BASE_URL: "http://core.internal.test:8080"
};

const TENANT_A = "11111111-1111-4111-8111-111111111111";
const TENANT_B = "33333333-3333-4333-8333-333333333333";
const ACTOR_A = "22222222-2222-4222-8222-222222222222";
const ACTOR_B = "44444444-4444-4444-8444-444444444444";

async function withEnv(vars, fn) {
  const prior = {};
  for (const key of ENV_KEYS) {
    prior[key] = process.env[key];
    delete process.env[key];
  }
  Object.assign(process.env, vars);
  resetSessionStoreForTesting();
  try {
    return await fn();
  } finally {
    for (const key of ENV_KEYS) {
      if (prior[key] === undefined) delete process.env[key];
      else process.env[key] = prior[key];
    }
    resetSessionStoreForTesting();
  }
}

function recordingFetch() {
  const calls = [];
  const impl = async () =>
    new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  return {
    calls,
    impl: async (url, init) => {
      calls.push({
        url: String(url),
        headers: init?.headers instanceof Headers ? init.headers : new Headers(init?.headers ?? {})
      });
      return impl();
    }
  };
}

async function withFetch(recorder, fn) {
  const prior = globalThis.fetch;
  globalThis.fetch = recorder.impl;
  try {
    return await fn();
  } finally {
    globalThis.fetch = prior;
  }
}

async function sessionFor(tenantId, actorId, permissions) {
  const { sessionId } = await persistOperatorSession({ tenantId, actorId, permissions });
  return sessionId;
}

test("server in-process read signs tenant A authority exactly once", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["REVIEW_READ"]);
    await withFetch(recorder, async () => {
      const response = await bffServerReadWithSessionCookie(sessionId, "/api/v1/quote-review/queue");
      assert.equal(response.status, 200);
      assert.equal(recorder.calls.length, 1);
      assert.equal(recorder.calls[0].headers.get("x-tenant-id"), TENANT_A);
      assert.ok(recorder.calls[0].headers.get("x-orderpilot-gateway-signature"));
    });
  });
});

test("concurrent server reads isolate tenant A and tenant B authority", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionA = await sessionFor(TENANT_A, ACTOR_A, ["REVIEW_READ"]);
    const sessionB = await sessionFor(TENANT_B, ACTOR_B, ["REVIEW_READ"]);
    await withFetch(recorder, async () => {
      const [responseA, responseB] = await Promise.all([
        bffServerReadWithSessionCookie(sessionA, "/api/v1/quote-review/queue"),
        bffServerReadWithSessionCookie(sessionB, "/api/v1/quote-review/queue")
      ]);
      assert.equal(responseA.status, 200);
      assert.equal(responseB.status, 200);
      assert.equal(recorder.calls.length, 2);
      const tenants = recorder.calls.map((call) => call.headers.get("x-tenant-id")).sort();
      assert.deepEqual(tenants, [TENANT_A, TENANT_B].sort());
    });
  });
});

test("missing server session is denied with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    await withFetch(recorder, async () => {
      const response = await bffServerReadWithSessionCookie(null, "/api/v1/quote-review/queue");
      assert.equal(response.status, 401);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("opaque session cookie is not a signed tenant payload", async () => {
  await withEnv(BFF_ENV, async () => {
    const { sessionId } = await persistOperatorSession({
      tenantId: TENANT_A,
      actorId: ACTOR_A,
      permissions: ["REVIEW_READ"]
    });
    assert.ok(!sessionId.includes(TENANT_A));
    assert.ok(!sessionId.includes(ACTOR_A));
    assert.ok(!sessionId.includes("REVIEW_READ"));
    assert.doesNotMatch(sessionId, /\./);
  });
});

test("architecture: dashboard-http does not import next/headers", () => {
  const source = readFileSync(join(root, "lib", "dashboard-http.ts"), "utf8");
  const browser = readFileSync(join(root, "lib", "dashboard-http.browser.ts"), "utf8");
  assert.doesNotMatch(source, /next\/headers/);
  assert.doesNotMatch(browser, /next\/headers/);
  assert.doesNotMatch(browser, /server-only/);
});

test("architecture: dashboard-http.browser has no next/headers", () => {
  const source = readFileSync(join(root, "lib", "dashboard-http.ts"), "utf8");
  const browser = readFileSync(join(root, "lib", "dashboard-http.browser.ts"), "utf8");
  assert.doesNotMatch(source, /next\/headers/);
  assert.doesNotMatch(browser, /next\/headers/);
  assert.doesNotMatch(browser, /dashboard-server-bff/);
});

test("architecture: server tenant reads use tenant-get-json.server", () => {
  const source = readFileSync(join(root, "lib", "server", "intake-api.server.ts"), "utf8");
  assert.match(source, /tenant-get-json\.server/);
  assert.match(source, /\.server/);
});

test("architecture: in-process server read stays in bff-server-read", () => {
  const source = readFileSync(join(root, "lib", "bff", "bff-server-read.ts"), "utf8");
  assert.doesNotMatch(source, /next\/headers/);
  assert.doesNotMatch(source, /document|window/);
});
