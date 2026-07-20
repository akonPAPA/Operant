/**
 * Parameterized BFF authorization matrix for tenant quote mutation routes.
 */
import assert from "node:assert/strict";
import test from "node:test";
import { proxyCoreRequest } from "../lib/bff/bff-proxy.ts";
import {
  persistOperatorSession,
  resetSessionStoreForTesting,
  revokeOperatorSession,
  setRedisClientFactoryForTesting
} from "../lib/bff/bff-session-store.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_GATEWAY_SHARED_SECRET",
  "ORDERPILOT_PUBLIC_ORIGIN",
  "CORE_API_BASE_URL"
];

const BFF_ENV = {
  NODE_ENV: "test",
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_GATEWAY_SHARED_SECRET: "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517",
  ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard.test",
  CORE_API_BASE_URL: "http://core.internal.test:8080"
};

const QUOTE_ID = "44444444-4444-4444-8444-444444444444";
const CSRF = "csrf-token-0123456789abcdef";

const QUOTE_MUTATION_ROUTES = [
  {
    name: "from-rfq",
    path: "/api/bff/api/v1/quotes/from-rfq",
    body: JSON.stringify({
      customerExternalRef: "CUST-001",
      requestedLocation: "WH-ALM",
      requestedDiscountPercent: 0,
      requestedItems: [{ rawSkuOrAlias: "SKU-1", description: "Item", quantity: 1, uom: "EA" }]
    })
  },
  {
    name: "approve",
    path: `/api/bff/api/v1/quotes/${QUOTE_ID}/approve`,
    body: JSON.stringify({ reason: "Approved for demo", comment: "Approved for demo" })
  },
  {
    name: "reject",
    path: `/api/bff/api/v1/quotes/${QUOTE_ID}/reject`,
    body: JSON.stringify({ reason: "Not acceptable", comment: "Not acceptable" })
  },
  {
    name: "request-changes",
    path: `/api/bff/api/v1/quotes/${QUOTE_ID}/request-changes`,
    body: JSON.stringify({ reason: "Fix qty", comment: "Fix qty" })
  },
  {
    name: "convert-to-internal-order",
    path: `/api/bff/api/v1/quotes/${QUOTE_ID}/convert-to-internal-order`,
    body: JSON.stringify({ reason: "Convert", comment: "Convert" })
  }
];

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
    setRedisClientFactoryForTesting(null);
  }
}

function recordingFetch(response) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url: String(url), init });
    return (
      response ??
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      })
    );
  };
  return { calls, impl };
}

async function withFetch(fetchImpl, fn) {
  const prior = globalThis.fetch;
  globalThis.fetch = fetchImpl;
  try {
    return await fn();
  } finally {
    globalThis.fetch = prior;
  }
}

async function operatorSession(permissions = ["QUOTE_READ", "QUOTE_ACTION"]) {
  const { sessionId } = await persistOperatorSession({
    tenantId: "11111111-1111-4111-8111-111111111111",
    actorId: "22222222-2222-4222-8222-222222222222",
    permissions
  });
  return sessionId;
}

function bffRequest(path, options = {}) {
  const { sessionId, method = "POST", csrf = CSRF, body, extraHeaders } = options;
  const headers = {
    host: "dashboard.test",
    origin: "https://dashboard.test",
    ...(sessionId ? { cookie: `op_session=${sessionId}; op_csrf=${CSRF}` } : {}),
    ...(csrf ? { "X-OP-CSRF-Token": csrf } : {}),
    ...(body ? { "Content-Type": "application/json" } : {}),
    ...(extraHeaders ?? {})
  };
  return new Request(`https://dashboard.test${path}`, { method, headers, body });
}

async function proxied(path, options = {}) {
  const request = bffRequest(path, options);
  const segments = path.replace(/^\/api\/bff\//, "").split("?")[0].split("/");
  return proxyCoreRequest(request, segments);
}

for (const route of QUOTE_MUTATION_ROUTES) {
  test(`quote mutation ${route.name}: missing session → denied, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(route.path, { sessionId: undefined, body: route.body })
      );
      assert.ok(response.status === 401 || response.status === 403);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: revoked session → denied, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession();
      await revokeOperatorSession(sessionId);
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () => proxied(route.path, { sessionId, body: route.body }));
      assert.ok(response.status === 401 || response.status === 403);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: QUOTE_READ without QUOTE_ACTION → 403, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession(["QUOTE_READ"]);
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () => proxied(route.path, { sessionId, body: route.body }));
      assert.equal(response.status, 403);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: invalid JSON body → 400, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession();
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(route.path, { sessionId, body: "{not-json" })
      );
      assert.equal(response.status, 400);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: spoofed tenantId in body does not override session tenant header`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
      const { calls, impl } = recordingFetch();
      const poison = JSON.stringify({ ...JSON.parse(route.body), tenantId: "99999999-9999-4999-8999-999999999999" });
      const response = await withFetch(impl, () =>
        proxied(route.path, {
          sessionId,
          body: poison,
          extraHeaders: { "Idempotency-Key": "quote-matrix-key-spoof" }
        })
      );
      assert.equal(response.status, 200);
      assert.equal(calls.length, 1);
      assert.equal(calls[0].init.headers.get("x-tenant-id"), "11111111-1111-4111-8111-111111111111");
    });
  });

  test(`quote mutation ${route.name}: valid QUOTE_ACTION → exactly one Core call`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(route.path, {
          sessionId,
          body: route.body,
          extraHeaders: { "Idempotency-Key": "quote-matrix-key-1" }
        })
      );
      assert.equal(response.status, 200);
      assert.equal(calls.length, 1);
      assert.equal(calls[0].init.headers.get("X-OrderPilot-Permissions"), "QUOTE_ACTION");
      assert.equal(calls[0].init.headers.get("Authorization"), null);
      assert.notEqual(calls[0].init.headers.get("X-OrderPilot-Permissions"), "STAFF_SUPPORT_READ");
    });
  });

  test(`quote mutation ${route.name}: browser permission header cannot widen upstream grant`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(route.path, {
          sessionId,
          body: route.body,
          extraHeaders: {
            "Idempotency-Key": "quote-matrix-key-2",
            Authorization: "Bearer forged-token",
            "X-OrderPilot-Permissions": "STAFF_SUPPORT_READ"
          }
        })
      );
      assert.equal(response.status, 200);
      assert.equal(calls.length, 1);
      assert.equal(calls[0].init.headers.get("Authorization"), null);
      assert.equal(calls[0].init.headers.get("X-OrderPilot-Permissions"), "QUOTE_ACTION");
    });
  });
}


test("quote mutation: oversized body → 413, zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const oversized = JSON.stringify({
      ...JSON.parse(QUOTE_MUTATION_ROUTES[0].body),
      requestedItems: [{ rawSkuOrAlias: "x".repeat(300 * 1024), description: "d", quantity: 1, uom: "EA" }]
    });
    const response = await withFetch(impl, () =>
      proxied(QUOTE_MUTATION_ROUTES[0].path, { sessionId, body: oversized })
    );
    assert.equal(response.status, 413);
    assert.equal(calls.length, 0);
  });
});

test("quote mutation: raw Core error body is not returned to caller", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const hostile = new Response("java.lang.NullPointerException at com.orderpilot.internal.Secret", {
      status: 500,
      headers: { "Content-Type": "text/plain", "X-Internal-Trace": "secret" }
    });
    const { calls, impl } = recordingFetch(hostile);
    const response = await withFetch(impl, () =>
      proxied(QUOTE_MUTATION_ROUTES[0].path, {
        sessionId,
        body: QUOTE_MUTATION_ROUTES[0].body,
        extraHeaders: { "Idempotency-Key": "quote-matrix-key-3" }
      })
    );
    assert.equal(calls.length, 1);
    const text = await response.text();
    assert.doesNotMatch(text, /NullPointerException|SecretService|X-Internal-Trace/);
  });
});
