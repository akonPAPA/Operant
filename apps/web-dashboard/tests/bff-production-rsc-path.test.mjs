import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { BFF_SESSION_COOKIE } from "../lib/bff/bff-config.ts";
import {
  expireOperatorSessionForTesting,
  persistOperatorSession,
  resetSessionStoreForTesting,
  revokeOperatorSession
} from "../lib/bff/bff-session-store.ts";
import { dashboardServerBffFetchWithCookieHeader } from "../lib/server/dashboard-server-bff-fetch.ts";

const INBOX_READ_PATH = "/api/v1/intake/messages";

async function probeInboxServerRead(cookieHeader) {
  const response = await dashboardServerBffFetchWithCookieHeader(cookieHeader, INBOX_READ_PATH);
  if (!response.ok) {
    return { data: [], error: `Core API returned ${response.status}.` };
  }
  return { data: await response.json(), error: undefined };
}

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
  NODE_ENV: "production",
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

function sessionCookieHeader(sessionId) {
  return `${BFF_SESSION_COOKIE}=${encodeURIComponent(sessionId)}`;
}

function recordingFetch() {
  const calls = [];
  return {
    calls,
    impl: async (url, init) => {
      calls.push({
        url: String(url),
        headers: init?.headers instanceof Headers ? init.headers : new Headers(init?.headers ?? {})
      });
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
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

async function sessionFor(tenantId, actorId, permissions, expiresAtEpochSec) {
  const { sessionId } = await persistOperatorSession({
    tenantId,
    actorId,
    permissions,
    ...(expiresAtEpochSec !== undefined ? { expiresAtEpochSec } : {})
  });
  return sessionId;
}

test("production inbox read-model getIntakeMessages signs tenant A via in-process BFF", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    await withFetch(recorder, async () => {
      const result = await probeInboxServerRead(sessionCookieHeader(sessionId));
      assert.equal(result.error, undefined);
      assert.equal(recorder.calls.length, 1);
      assert.ok(recorder.calls[0].url.includes("core.internal.test"));
      assert.doesNotMatch(recorder.calls[0].url, /\/api\/bff/);
      assert.equal(recorder.calls[0].headers.get("x-tenant-id"), TENANT_A);
      assert.ok(recorder.calls[0].headers.get("x-orderpilot-gateway-signature"));
    });
  });
});

test("concurrent production read-models isolate tenant A and tenant B", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionA = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    const sessionB = await sessionFor(TENANT_B, ACTOR_B, ["INTAKE_READ"]);
    await withFetch(recorder, async () => {
      const [responseA, responseB] = await Promise.all([
        dashboardServerBffFetchWithCookieHeader(
          sessionCookieHeader(sessionA),
          "/api/v1/intake/messages"
        ),
        dashboardServerBffFetchWithCookieHeader(
          sessionCookieHeader(sessionB),
          "/api/v1/intake/messages"
        )
      ]);
      assert.equal(responseA.status, 200);
      assert.equal(responseB.status, 200);
      assert.equal(recorder.calls.length, 2);
      const tenants = recorder.calls.map((c) => c.headers.get("x-tenant-id")).sort();
      assert.deepEqual(tenants, [TENANT_A, TENANT_B].sort());
    });
  });
});

test("missing session denies inbox read-model with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    await withFetch(recorder, async () => {
      const result = await probeInboxServerRead(null);
      assert.match(result.error ?? "", /401/);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("expired session denies with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    expireOperatorSessionForTesting(sessionId);
    await withFetch(recorder, async () => {
      const result = await probeInboxServerRead(sessionCookieHeader(sessionId));
      assert.match(result.error ?? "", /401/);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("revoked session denies with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    await revokeOperatorSession(sessionId);
    await withFetch(recorder, async () => {
      const result = await probeInboxServerRead(sessionCookieHeader(sessionId));
      assert.match(result.error ?? "", /401/);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("wrong permission denies with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["REVIEW_READ"]);
    await withFetch(recorder, async () => {
      const result = await probeInboxServerRead(sessionCookieHeader(sessionId));
      assert.match(result.error ?? "", /403/);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("unknown route denies with zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    const cookie = sessionCookieHeader(sessionId);
    await withFetch(recorder, async () => {
      const response = await dashboardServerBffFetchWithCookieHeader(
        cookie,
        "/api/v1/not-a-registered-route",
        { method: "GET" }
      );
      assert.equal(response.status, 404);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("server transport rejects mutations and never reaches Core", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    const cookie = sessionCookieHeader(sessionId);
    await withFetch(recorder, async () => {
      const response = await dashboardServerBffFetchWithCookieHeader(cookie, "/api/v1/intake/messages", {
        method: "POST",
        body: "{}"
      });
      assert.equal(response.status, 403);
      assert.equal(recorder.calls.length, 0);
    });
  });
});

test("server transport strips Authorization and arbitrary Cookie forwarding", async () => {
  await withEnv(BFF_ENV, async () => {
    const recorder = recordingFetch();
    const sessionId = await sessionFor(TENANT_A, ACTOR_A, ["INTAKE_READ"]);
    const cookie = sessionCookieHeader(sessionId);
    await withFetch(recorder, async () => {
      await dashboardServerBffFetchWithCookieHeader(cookie, "/api/v1/intake/messages", {
        method: "GET",
        headers: {
          Authorization: "Bearer evil",
          Cookie: "other=1; op_session=tampered",
          "X-Tenant-Id": TENANT_B
        }
      });
      assert.equal(recorder.calls.length, 1);
      assert.equal(recorder.calls[0].headers.get("authorization"), null);
      assert.equal(recorder.calls[0].headers.get("x-tenant-id"), TENANT_A);
    });
  });
});

test("architecture: server transport is not imported from dashboard-http.browser", () => {
  const browser = readFileSync(join(root, "lib", "dashboard-http.browser.ts"), "utf8");
  assert.doesNotMatch(browser, /next\/headers/);
  assert.doesNotMatch(browser, /dashboard-server-bff-transport/);
});

test("architecture: server fetch module excludes browser CSRF/document APIs", () => {
  const source = readFileSync(join(root, "lib", "server", "dashboard-server-bff-fetch.ts"), "utf8");
  assert.doesNotMatch(source, /browser-csrf-cookie/);
  assert.doesNotMatch(source, /\bdocument\b/);
  assert.match(source, /cannot be imported in the browser/);
});

test("architecture: dashboard-api-fetch uses dynamic server import only on server branch", () => {
  const source = readFileSync(join(root, "lib", "server", "tenant-get-json.server.ts"), "utf8");
  assert.match(source, /dashboard-server-bff-fetch/);
  assert.doesNotMatch(source, /next\/headers/);
});
