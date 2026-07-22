/**
 * Parameterized BFF authorization matrix for tenant quote mutation routes.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
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

// Merge an extra own key into a valid JSON object body.
function withExtraKey(bodyJson, extra) {
  return JSON.stringify({ ...JSON.parse(bodyJson), ...extra });
}

// Splice a raw literal key/value in front of an existing JSON object body. Object-literal spread
// cannot create an own "__proto__" key (it sets the prototype), so prototype-pollution keys must
// be injected as raw JSON text to reproduce a real hostile payload.
function withRawKey(bodyJson, rawKeyValue) {
  return `{${rawKeyValue},${bodyJson.slice(1)}`;
}

// The strict request-field matrix required by the STATE1 quote-authority contract. Each case
// mutates an otherwise-valid body so the ONLY reason for rejection is the offending key.
const REJECTION_CASES = [
  { label: "unknown extra field", poison: (b) => withExtraKey(b, { unexpectedField: "x" }) },
  { label: "tenantId authority field", poison: (b) => withExtraKey(b, { tenantId: "99999999-9999-4999-8999-999999999999" }) },
  { label: "actorId authority field", poison: (b) => withExtraKey(b, { actorId: "99999999-9999-4999-8999-999999999999" }) },
  { label: "approvedBy authority field", poison: (b) => withExtraKey(b, { approvedBy: "operator-1" }) },
  { label: "createdBy authority field", poison: (b) => withExtraKey(b, { createdBy: "operator-1" }) },
  { label: "permission authority field", poison: (b) => withExtraKey(b, { permission: "QUOTE_ACTION" }) },
  { label: "role authority field", poison: (b) => withExtraKey(b, { role: "TENANT_ADMIN" }) },
  { label: "status server-state field", poison: (b) => withExtraKey(b, { status: "APPROVED" }) },
  { label: "approvalStatus server-state field", poison: (b) => withExtraKey(b, { approvalStatus: "APPROVED" }) },
  { label: "executionStatus server-state field", poison: (b) => withExtraKey(b, { executionStatus: "EXECUTED" }) },
  { label: "__proto__ prototype key", poison: (b) => withRawKey(b, '"__proto__":{"polluted":true}') },
  { label: "constructor prototype key", poison: (b) => withRawKey(b, '"constructor":{"polluted":true}') },
  { label: "prototype prototype key", poison: (b) => withRawKey(b, '"prototype":{"polluted":true}') }
];

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

  test(`quote mutation ${route.name}: invalid CSRF → 403, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession();
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(route.path, { sessionId, body: route.body, csrf: "forged-csrf-token-0123456789abcdef" })
      );
      assert.equal(response.status, 403);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: invalid Origin → 403, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession();
      const { calls, impl } = recordingFetch();
      const request = new Request(`https://dashboard.test${route.path}`, {
        method: "POST",
        headers: {
          host: "dashboard.test",
          origin: "https://evil.example",
          cookie: `op_session=${sessionId}; op_csrf=${CSRF}`,
          "X-OP-CSRF-Token": CSRF,
          "Content-Type": "application/json"
        },
        body: route.body
      });
      const segments = route.path.replace(/^\/api\/bff\//, "").split("?")[0].split("/");
      const response = await withFetch(impl, () => proxyCoreRequest(request, segments));
      assert.equal(response.status, 403);
      assert.equal(calls.length, 0);
    });
  });

  test(`quote mutation ${route.name}: unsupported Content-Type → 415, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession();
      const { calls, impl } = recordingFetch();
      const request = new Request(`https://dashboard.test${route.path}`, {
        method: "POST",
        headers: {
          host: "dashboard.test",
          origin: "https://dashboard.test",
          cookie: `op_session=${sessionId}; op_csrf=${CSRF}`,
          "X-OP-CSRF-Token": CSRF,
          "Content-Type": "text/plain"
        },
        body: route.body
      });
      const segments = route.path.replace(/^\/api\/bff\//, "").split("?")[0].split("/");
      const response = await withFetch(impl, () => proxyCoreRequest(request, segments));
      assert.equal(response.status, 415);
      assert.equal(calls.length, 0);
    });
  });

  // STATE1 strict request-field contract: any unknown / authority / server-state /
  // prototype-pollution key fails closed at the BFF with 400 and ZERO Core calls, so a spoofed
  // tenantId (or any other non-allowlisted key) never even reaches the tenant-header build step.
  for (const rejection of REJECTION_CASES) {
    test(`quote mutation ${route.name}: ${rejection.label} → 400, zero Core calls`, async () => {
      await withEnv(BFF_ENV, async () => {
        const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
        const { calls, impl } = recordingFetch();
        const response = await withFetch(impl, () =>
          proxied(route.path, {
            sessionId,
            body: rejection.poison(route.body),
            extraHeaders: { "Idempotency-Key": `quote-matrix-reject-${route.name}` }
          })
        );
        assert.equal(response.status, 400);
        assert.equal(calls.length, 0);
      });
    });
  }

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


test("quote mutation from-rfq: nested unknown array-item field → 400, zero Core calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const body = JSON.stringify({
      customerExternalRef: "CUST-001",
      requestedLocation: "WH-ALM",
      requestedDiscountPercent: 0,
      requestedItems: [{ rawSkuOrAlias: "SKU-1", description: "Item", quantity: 1, uom: "EA", nestedExtra: "x" }]
    });
    const response = await withFetch(impl, () =>
      proxied(QUOTE_MUTATION_ROUTES[0].path, { sessionId, body })
    );
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  });
});

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

// ==========================================================================================
// F1 security-order regression proof (STEP 2 / P03). These fail on the defective head, where the
// route handler read + schema-validated the quote body BEFORE proxyCoreRequest authenticated the
// request (and read the body a second time). They assert BEHAVIOUR, not source shape.
// ==========================================================================================

const FROM_RFQ_PATH = "/api/bff/api/v1/quotes/from-rfq";
const FROM_RFQ_BODY = QUOTE_MUTATION_ROUTES[0].body;

function segmentsOf(path) {
  return path.replace(/^\/api\/bff\//, "").split("?")[0].split("/");
}

// A quote mutation Request whose body is instrumented WITHOUT a stream body constructor (Node eagerly
// pulls a ReadableStream body at Request construction, which would falsely read as "consumed"). The
// real Request carries a normal string body; a Proxy intercepts `.body` and hands the proxy code a
// spy stream that flips `spy.read` true the instant the PROXY calls getReader().read(). So `spy.read`
// isolates consumption performed by proxyCoreRequest itself — the exact thing the invariant requires.
function observableBodyRequest(path, options = {}) {
  const {
    sessionId,
    csrf = CSRF,
    origin = "https://dashboard.test",
    contentType = "application/json",
    payload = "{}",
    idempotencyKey = "quote-observable-key-1"
  } = options;
  const spy = { read: false, cancelled: false };
  const data = new TextEncoder().encode(payload);
  let delivered = false;
  const spyBody = new ReadableStream(
    {
      pull(controller) {
        spy.read = true;
        if (!delivered) {
          delivered = true;
          controller.enqueue(data);
        }
        controller.close();
      },
      cancel() {
        spy.cancelled = true;
      }
    },
    // highWaterMark 0: never auto-pull to pre-fill the queue — only a real getReader().read() by
    // the proxy flips spy.read, so this measures consumption performed by proxyCoreRequest itself.
    { highWaterMark: 0 }
  );
  const headers = {
    host: "dashboard.test",
    origin,
    ...(sessionId ? { cookie: `op_session=${sessionId}; op_csrf=${CSRF}` } : {}),
    ...(csrf ? { "X-OP-CSRF-Token": csrf } : {}),
    ...(contentType ? { "Content-Type": contentType } : {}),
    ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {})
  };
  const real = new Request(`https://dashboard.test${path}`, { method: "POST", headers, body: payload });
  const request = new Proxy(real, {
    get(target, prop) {
      if (prop === "body") {
        return spyBody;
      }
      const value = target[prop];
      return typeof value === "function" ? value.bind(target) : value;
    }
  });
  return { request, spy };
}

test("F1-A: missing session denies before any body byte is consumed (401, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const { calls, impl } = recordingFetch();
    const { request, spy } = observableBodyRequest(FROM_RFQ_PATH, {
      sessionId: undefined,
      payload: FROM_RFQ_BODY,
      throwOnRead: true
    });
    const response = await withFetch(impl, () => proxyCoreRequest(request, segmentsOf(FROM_RFQ_PATH)));
    assert.equal(response.status, 401);
    assert.equal(spy.read, false, "body stream must not be read before authentication");
    assert.equal(calls.length, 0);
  });
});

test("F1-B: missing QUOTE_ACTION denies before body is consumed (403, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ"]);
    const { calls, impl } = recordingFetch();
    const { request, spy } = observableBodyRequest(FROM_RFQ_PATH, {
      sessionId,
      payload: FROM_RFQ_BODY,
      throwOnRead: true
    });
    const response = await withFetch(impl, () => proxyCoreRequest(request, segmentsOf(FROM_RFQ_PATH)));
    assert.equal(response.status, 403);
    assert.equal(spy.read, false, "body stream must not be read before permission check");
    assert.equal(calls.length, 0);
  });
});

test("F1-C: invalid CSRF denies before body is consumed (403, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    const { request, spy } = observableBodyRequest(FROM_RFQ_PATH, {
      sessionId,
      payload: FROM_RFQ_BODY,
      csrf: "forged-csrf-token-0123456789abcdef",
      throwOnRead: true
    });
    const response = await withFetch(impl, () => proxyCoreRequest(request, segmentsOf(FROM_RFQ_PATH)));
    assert.equal(response.status, 403);
    assert.equal(spy.read, false, "body stream must not be read before CSRF check");
    assert.equal(calls.length, 0);
  });
});

test("F1-C: invalid Origin denies before body is consumed (403, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    const { request, spy } = observableBodyRequest(FROM_RFQ_PATH, {
      sessionId,
      payload: FROM_RFQ_BODY,
      origin: "https://evil.example",
      throwOnRead: true
    });
    const response = await withFetch(impl, () => proxyCoreRequest(request, segmentsOf(FROM_RFQ_PATH)));
    assert.equal(response.status, 403);
    assert.equal(spy.read, false, "body stream must not be read before same-origin check");
    assert.equal(calls.length, 0);
  });
});

test("F1-D: valid mutation reads the one-shot body exactly once and forwards the canonical body", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    const { request, spy } = observableBodyRequest(FROM_RFQ_PATH, {
      sessionId,
      payload: FROM_RFQ_BODY,
      idempotencyKey: "quote-observable-valid-1"
    });
    const response = await withFetch(impl, () => proxyCoreRequest(request, segmentsOf(FROM_RFQ_PATH)));
    assert.equal(response.status, 200);
    assert.equal(spy.read, true, "the authenticated body must be read exactly once");
    assert.equal(calls.length, 1);
    // Canonical body signed and forwarded is exactly JSON.stringify(parsed) — no raw ambiguous bytes.
    assert.equal(calls[0].init.body, JSON.stringify(JSON.parse(FROM_RFQ_BODY)));
  });
});

test("F1-D-guard: proxy has no Request.clone()-based second read and exactly one bounded read", () => {
  const proxySrc = readFileSync(join(process.cwd(), "lib/bff/bff-proxy.ts"), "utf8");
  const routeSrc = readFileSync(join(process.cwd(), "app/api/bff/[...segments]/route.ts"), "utf8");
  assert.doesNotMatch(proxySrc, /\.clone\(/, "proxy must not clone the request for a second read");
  assert.doesNotMatch(routeSrc, /\.clone\(/, "route handler must not clone/parse the request body");
  const reads = proxySrc.match(/readRequestBodyBytesBounded\(request/g) ?? [];
  assert.equal(reads.length, 1, "exactly one bounded request-body read in the proxy path");
});

// F1-E: strict-contract cases that must be denied AFTER authentication (400/413/415, zero Core).
// The unknown/authority/prototype-pollution and invalid-JSON/oversized/wrong-media cases already
// live in the matrix above; these add the quote-schema, duplicate-key and invalid-UTF-8 cases that
// used to be enforced only by the pre-auth route handler.
const AUTHENTICATED_BODY_REJECTIONS = [
  {
    label: "duplicate JSON key",
    status: 400,
    body: `{"customerExternalRef":"CUST-A","customerExternalRef":"CUST-B","requestedItems":[{"rawSkuOrAlias":"SKU","quantity":1,"uom":"EA"}]}`
  },
  {
    label: "invalid UTF-8 bytes",
    status: 400,
    body: Buffer.from([0x7b, 0x22, 0x61, 0x22, 0x3a, 0xff, 0x7d])
  },
  {
    label: "out-of-range requestedDiscountPercent (schema)",
    status: 400,
    body: JSON.stringify({
      customerExternalRef: "CUST-1",
      requestedDiscountPercent: 101,
      requestedItems: [{ rawSkuOrAlias: "SKU-1", quantity: 1, uom: "EA" }]
    })
  },
  {
    label: "reject without reason or comment (schema)",
    status: 400,
    path: `/api/bff/api/v1/quotes/${QUOTE_ID}/reject`,
    body: JSON.stringify({})
  }
];

for (const rejection of AUTHENTICATED_BODY_REJECTIONS) {
  test(`F1-E: authenticated ${rejection.label} → ${rejection.status}, zero Core calls`, async () => {
    await withEnv(BFF_ENV, async () => {
      const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
      const { calls, impl } = recordingFetch();
      const response = await withFetch(impl, () =>
        proxied(rejection.path ?? FROM_RFQ_PATH, {
          sessionId,
          body: rejection.body,
          extraHeaders: { "Idempotency-Key": "quote-f1e-key" }
        })
      );
      assert.equal(response.status, rejection.status);
      assert.equal(calls.length, 0);
    });
  });
}

test("F1-F: missing required Idempotency-Key denies before signing/Core (400, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    // Valid body, valid auth, but no Idempotency-Key: quote-authority routes require one.
    const response = await withFetch(impl, () => proxied(FROM_RFQ_PATH, { sessionId, body: FROM_RFQ_BODY }));
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("F1-F: invalid Idempotency-Key denies before signing/Core (400, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied(FROM_RFQ_PATH, {
        sessionId,
        body: FROM_RFQ_BODY,
        extraHeaders: { "Idempotency-Key": "bad key with spaces" }
      })
    );
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("F1-F: valid opaque Idempotency-Key is forwarded byte-for-byte", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["QUOTE_READ", "QUOTE_ACTION"]);
    const { calls, impl } = recordingFetch();
    const opaqueKey = "op-idem.KEY_1:abc-DEF";
    const response = await withFetch(impl, () =>
      proxied(FROM_RFQ_PATH, {
        sessionId,
        body: FROM_RFQ_BODY,
        extraHeaders: { "Idempotency-Key": opaqueKey }
      })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.headers.get("Idempotency-Key"), opaqueKey);
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
