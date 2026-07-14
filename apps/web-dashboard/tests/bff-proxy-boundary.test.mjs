import assert from "node:assert/strict";
import test from "node:test";
import { canonicalizeJsonRequestBody, proxyCoreRequest, rawBffPathRejected, validateBffProductionConfig } from "../lib/bff/bff-proxy.ts";
import {
  bffGatewayClockSkewSeconds,
  bffUpstreamTimeoutMs,
  parseStrictBoundedInteger,
  validatedCoreApiInternalBaseUrl
} from "../lib/bff/bff-config.ts";
import {
  matchBffRoute,
  registeredBffRoutes
} from "../lib/bff/bff-route-registry.ts";
import {
  IDEMPOTENCY_KEY_CONTRACT,
  isCanonicalIdempotencyKey,
  resolveIdempotencyKey
} from "../lib/bff/bff-idempotency-key.ts";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  persistOperatorSession,
  resetSessionStoreForTesting,
  setRedisClientFactoryForTesting
} from "../lib/bff/bff-session-store.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_GATEWAY_SHARED_SECRET",
  "ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET",
  "ORDERPILOT_PUBLIC_ORIGIN",
  "CORE_API_BASE_URL",
  "ORDERPILOT_BFF_REDIS_URL",
  "REDIS_URL",
  "ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS",
  "ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS"
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
      if (prior[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prior[key];
      }
    }
    resetSessionStoreForTesting();
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
        headers: {
          "Content-Type": "application/json",
          "Set-Cookie": "core_internal=evil; Path=/",
          "X-Internal-Trace": "core-debug-trace",
          "X-Powered-By": "core"
        }
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

async function operatorSession(permissions = ["REVIEW_READ", "REVIEW_ACTION"]) {
  const { sessionId } = await persistOperatorSession({
    tenantId: "11111111-1111-4111-8111-111111111111",
    actorId: "22222222-2222-4222-8222-222222222222",
    permissions
  });
  return sessionId;
}

const CSRF = "csrf-token-0123456789abcdef";

function bffRequest(path, options = {}) {
  const { sessionId, method = "GET", csrf, origin, body, contentType, extraHeaders } = options;
  const headers = {
    host: "dashboard.test",
    ...(origin === undefined && method !== "GET" ? { origin: "https://dashboard.test" } : {}),
    ...(origin ? { origin } : {}),
    ...(sessionId ? { cookie: `op_session=${sessionId}; op_csrf=${CSRF}` } : {}),
    ...(csrf ? { "X-OP-CSRF-Token": csrf } : {}),
    ...(body ? { "Content-Type": contentType ?? "application/json" } : {}),
    ...(extraHeaders ?? {})
  };
  return new Request(`https://dashboard.test${path}`, { method, headers, body });
}

function segmentsOf(path) {
  return path.replace(/^\/api\/bff\//, "").split("?")[0].split("/");
}

async function proxied(path, options = {}) {
  const request = bffRequest(path, options);
  return proxyCoreRequest(request, options.segments ?? segmentsOf(path));
}

test("valid read reaches the registered upstream route exactly once with signed authority", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1, "upstream called exactly once");
    assert.equal(calls[0].url, "http://core.internal.test:8080/api/v1/quote-review/queue");
    const upstreamHeaders = calls[0].init.headers;
    assert.equal(upstreamHeaders.get("X-Tenant-Id"), "11111111-1111-4111-8111-111111111111");
    assert.equal(upstreamHeaders.get("X-OrderPilot-Actor-Id"), "22222222-2222-4222-8222-222222222222");
    assert.equal(upstreamHeaders.get("X-OrderPilot-Permissions"), "REVIEW_READ");
    assert.equal(upstreamHeaders.get("X-OrderPilot-Signature-Version"), "2");
    assert.match(upstreamHeaders.get("X-OrderPilot-Content-SHA256") ?? "", /^[0-9a-f]{64}$/);
    assert.equal(
      upstreamHeaders.get("X-OrderPilot-Content-SHA256"),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert.ok(upstreamHeaders.get("X-OrderPilot-Gateway-Signature"));
    assert.ok(upstreamHeaders.get("X-OrderPilot-Gateway-Nonce"));
  });
});

test("client-forged gateway signature headers are stripped and rebuilt by BFF", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {
        sessionId,
        extraHeaders: {
          "X-OrderPilot-Signature-Version": "1",
          "X-OrderPilot-Content-SHA256": "0".repeat(64),
          "X-OrderPilot-Gateway-Signature": "forged"
        }
      })
    );
    assert.equal(calls.length, 1);
    const headers = calls[0].init.headers;
    assert.equal(headers.get("X-OrderPilot-Signature-Version"), "2");
    assert.notEqual(headers.get("X-OrderPilot-Gateway-Signature"), "forged");
    assert.notEqual(headers.get("X-OrderPilot-Content-SHA256"), "0".repeat(64));
  });
});

test("browser-provided authority, credential, and hop-by-hop headers are never forwarded", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {
        sessionId,
        extraHeaders: {
          "X-Tenant-Id": "evil-tenant",
          "X-OrderPilot-Actor-Id": "evil-actor",
          "X-OrderPilot-Permissions": "STAFF_SUPPORT_READ",
          "X-OrderPilot-Staff-Grant": "true",
          "X-OrderPilot-Gateway-Signature": "forged",
          authorization: "Bearer stolen",
          "proxy-authorization": "Basic stolen",
          forwarded: "for=evil",
          "x-forwarded-for": "6.6.6.6",
          "x-forwarded-host": "evil.example",
          connection: "keep-alive",
          upgrade: "websocket"
        }
      })
    );
    assert.equal(calls.length, 1);
    const upstreamHeaders = calls[0].init.headers;
    // session authority wins — the browser header values must be absent
    assert.equal(upstreamHeaders.get("X-Tenant-Id"), "11111111-1111-4111-8111-111111111111");
    assert.equal(upstreamHeaders.get("X-OrderPilot-Permissions"), "REVIEW_READ");
    assert.notEqual(upstreamHeaders.get("X-OrderPilot-Permissions"), "STAFF_SUPPORT_READ");
    assert.equal(upstreamHeaders.get("authorization"), null);
    assert.equal(upstreamHeaders.get("proxy-authorization"), null);
    assert.equal(upstreamHeaders.get("cookie"), null, "session cookie never reaches Core");
    assert.equal(upstreamHeaders.get("forwarded"), null);
    assert.equal(upstreamHeaders.get("x-forwarded-for"), null);
    assert.equal(upstreamHeaders.get("x-forwarded-host"), null);
    assert.equal(upstreamHeaders.get("x-orderpilot-staff-grant"), null);
    assert.equal(upstreamHeaders.get("upgrade"), null);
    const signature = upstreamHeaders.get("X-OrderPilot-Gateway-Signature");
    assert.notEqual(signature, "forged");
  });
});

test("Core response internals are stripped: no Set-Cookie, no internal headers, no-store", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.headers.get("Set-Cookie"), null);
    assert.equal(response.headers.get("X-Internal-Trace"), null);
    assert.equal(response.headers.get("X-Powered-By"), null);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
  });
});

test("raw Core 5xx bodies are never exposed", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { impl } = recordingFetch(
      new Response("java.lang.NullPointerException at com.orderpilot.internal.Secret", {
        status: 500,
        headers: { "Content-Type": "text/plain" }
      })
    );
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 502);
    const body = await response.text();
    assert.doesNotMatch(body, /NullPointerException|com\.orderpilot/);
  });
});

test("no session -> 401 and upstream fetch call count remains 0", async () => {
  await withEnv(BFF_ENV, async () => {
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {})
    );
    assert.equal(response.status, 401);
    assert.equal(calls.length, 0);
  });
});

test("invalid or duplicate session cookie -> 401 and zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    for (const cookie of [
      `op_session=${sessionId}; op_session=${sessionId}`,
      `op_session=${sessionId}; op_session=${"T".repeat(43)}`,
      "op_session=",
      "op_session=%E0%A4%A",
      "op_session=short"
    ]) {
      const response = await withFetch(impl, () =>
        proxied("/api/bff/api/v1/quote-review/queue", { extraHeaders: { cookie } })
      );
      assert.equal(response.status, 401, cookie);
    }
    assert.equal(calls.length, 0);
  });
});

test("duplicate csrf cookie -> 403 and zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        method: "POST",
        csrf: CSRF,
        body: JSON.stringify({ reasonCode: "READY" }),
        extraHeaders: { cookie: `op_session=${sessionId}; op_csrf=${CSRF}; op_csrf=${CSRF}` }
      })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0);
  });
});
test("session without the registered permission -> 403, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["ANALYTICS_READ"]);
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0);
  });
});

test("unregistered route and wrong method -> 404, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const unknown = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/not-a-registered-route", { sessionId })
    );
    assert.equal(unknown.status, 404);
    const wrongMethod = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {
        sessionId,
        method: "DELETE",
        csrf: CSRF
      })
    );
    assert.equal(wrongMethod.status, 404);
    assert.equal(calls.length, 0);
  });
});

test("internal, support, staff, demo, webhook and public planes are denied", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["REVIEW_READ", "REVIEW_ACTION"]);
    const { calls, impl } = recordingFetch();
    const deniedPaths = [
      "/api/bff/api/v1/internal/support/tenants/search",
      "/api/bff/api/v1/internal/support/incidents",
      "/api/bff/api/v1/internal/support/tenants/t1/break-glass-requests/r1/approve",
      "/api/bff/api/v1/internal/support/tenants/t1/data-repair-requests/r1/execute",
      "/api/bff/api/v1/internal/support/tenants/t1/maintenance-records",
      "/api/bff/api/v1/internal/support/tenants/t1/diagnostics",
      "/api/bff/api/v1/demo/rfq-handoff",
      "/api/bff/api/v1/webhooks/telegram",
      "/api/bff/api/v1/public/order-tracking/token123"
    ];
    for (const path of deniedPaths) {
      const readResponse = await withFetch(impl, () => proxied(path, { sessionId }));
      assert.equal(readResponse.status, 404, `GET ${path} must be denied`);
      const writeResponse = await withFetch(impl, () =>
        proxied(path, { sessionId, method: "POST", csrf: CSRF, body: "{}" })
      );
      assert.equal(writeResponse.status, 404, `POST ${path} must be denied`);
    }
    assert.equal(calls.length, 0, "no denied plane request may reach Core");
  });
});

test("mutation without CSRF token -> 403, upstream fetch call count remains 0", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        body: JSON.stringify({ reasonCode: "READY" })
      })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0, "no Core mutation may occur");
  });
});

test("mutation with mismatched CSRF token -> 403, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: "different-token-9876543210fedcba",
        body: JSON.stringify({ reasonCode: "READY" })
      })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0);
  });
});

test("mutation with malformed CSRF token -> 403, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    for (const malformed of ["ab", "bad token with spaces!", "<script>", "x".repeat(300)]) {
      const response = await withFetch(impl, () =>
        proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
          sessionId,
          method: "POST",
          csrf: malformed,
          body: JSON.stringify({ reasonCode: "READY" })
        })
      );
      assert.equal(response.status, 403, `malformed token ${JSON.stringify(malformed)}`);
    }
    assert.equal(calls.length, 0);
  });
});

test("cross-origin mutation -> 403, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        origin: "https://attacker.example",
        body: JSON.stringify({ reasonCode: "READY" })
      })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0);
  });
});

test("valid CSRF mutation reaches the registered upstream route exactly once", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: JSON.stringify({ reasonCode: "READY" }),
        extraHeaders: { "Idempotency-Key": "op-key-123" }
      })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(
      calls[0].url,
      "http://core.internal.test:8080/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft"
    );
    assert.equal(calls[0].init.headers.get("Idempotency-Key"), "op-key-123");
    assert.equal(calls[0].init.method, "POST");
    assert.equal(calls[0].init.headers.get("X-OrderPilot-Permissions"), "REVIEW_ACTION");
  });
});

test("least-privilege signing: outbound permissions are only the matched route permission", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["REVIEW_READ", "REVIEW_ACTION", "ANALYTICS_READ"]);
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.headers.get("X-OrderPilot-Permissions"), "REVIEW_READ");
    assert.doesNotMatch(calls[0].init.headers.get("X-OrderPilot-Permissions") ?? "", /REVIEW_ACTION|ANALYTICS_READ/);
  });
});

test("missing required permission with unrelated grants -> 403, zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["ANALYTICS_READ", "BOT_READ"]);
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 403);
    assert.equal(calls.length, 0);
  });
});

test("registry permission and signed permission stay aligned for every registered rule", () => {
  for (const rule of registeredBffRoutes()) {
    assert.equal(typeof rule.permission, "string");
    assert.ok(rule.permission.length > 0);
    assert.equal(rule.permission.includes(","), false, `${rule.method} ${rule.pattern} must bind one permission`);
  }
});

test("canonical JSON body collapses duplicate keys and strips UTF-8 BOM", () => {
  const duplicate = canonicalizeJsonRequestBody(
    new TextEncoder().encode('{"quantity":1,"quantity":999,"note":"ok"}')
  );
  assert.equal(duplicate.ok, true);
  if (duplicate.ok) {
    assert.equal(duplicate.body, JSON.stringify({ quantity: 999, note: "ok" }));
  }
  const bom = canonicalizeJsonRequestBody(
    new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode('{"a":1}')])
  );
  assert.equal(bom.ok, true);
  if (bom.ok) {
    assert.equal(bom.body, JSON.stringify({ a: 1 }));
  }
  const invalidUtf8 = canonicalizeJsonRequestBody(new Uint8Array([0x7b, 0x22, 0x61, 0x22, 0x3a, 0xc3, 0x7d]));
  assert.equal(invalidUtf8.ok, false);
  const invalidJson = canonicalizeJsonRequestBody(new TextEncoder().encode("{not-json"));
  assert.equal(invalidJson.ok, false);
});

test("invalid UTF-8 and invalid JSON mutation bodies are rejected with zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const badUtf8 = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: Buffer.from([0x7b, 0x22, 0x61, 0x22, 0x3a, 0xc3, 0x7d])
      })
    );
    assert.equal(badUtf8.status, 400);
    assert.doesNotMatch(await badUtf8.text(), /\xc3|\\u00/);
    const badJson = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: "{not-json"
      })
    );
    assert.equal(badJson.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("duplicate-key JSON is normalized before upstream forward", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: '{"reasonCode":"FIRST","reasonCode":"READY"}'
      })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.body, JSON.stringify({ reasonCode: "READY" }));
    assert.equal(calls[0].init.headers.get("X-OrderPilot-Permissions"), "REVIEW_ACTION");
  });
});

test("wrong content type -> 415 and oversized body -> 413, no upstream call", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const wrongType = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: "reason=READY",
        contentType: "application/x-www-form-urlencoded"
      })
    );
    assert.equal(wrongType.status, 415);
    const oversized = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
        sessionId,
        method: "POST",
        csrf: CSRF,
        body: JSON.stringify({ note: "x".repeat(300 * 1024) })
      })
    );
    assert.equal(oversized.status, 413);
    assert.equal(calls.length, 0);
  });
});

test("encoded slash, dot segments, duplicate slash and malformed percent are rejected", async () => {
  assert.equal(rawBffPathRejected("/api/bff/api/v1/quote-review/%2Fqueue"), true);
  assert.equal(rawBffPathRejected("/api/bff/api/v1/%2e%2e/internal"), true);
  assert.equal(rawBffPathRejected("/api/bff/api//v1/quote-review/queue"), true);
  assert.equal(rawBffPathRejected("/api/bff/api/v1/quote-review/%zz"), true);
  assert.equal(rawBffPathRejected("/api/bff/api/v1/quote-review/%5Cqueue"), true);
  assert.equal(rawBffPathRejected("/api/bff/api/v1/quote-review/queue"), false);

  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/%2Fqueue", {
        sessionId,
        segments: ["api", "v1", "quote-review", "queue"]
      })
    );
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("production-like config requires explicit Core URL — no localhost fallback", async () => {
  await withEnv(
    {
      ...BFF_ENV,
      ORDERPILOT_DEPLOY_PROFILE: "production",
      ORDERPILOT_PUBLIC_ORIGIN: "https://operant.example.com",
      CORE_API_BASE_URL: "",
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      const error = await validateBffProductionConfig();
      assert.match(error, /CORE_API_BASE_URL/);
    }
  );
});

test("Core API base URL validation requires an exact origin", async () => {
  const validLocalCases = [
    ["https://core.internal.example", "https://core.internal.example"],
    ["https://core.internal.example:8443/", "https://core.internal.example:8443"],
    ["http://127.0.0.1:8080", "http://127.0.0.1:8080"],
    ["http://[::1]:8080", "http://[::1]:8080"]
  ];
  for (const [raw, expected] of validLocalCases) {
    await withEnv({ ...BFF_ENV, CORE_API_BASE_URL: raw }, () => {
      assert.equal(validatedCoreApiInternalBaseUrl(), expected, raw);
    });
  }

  for (const raw of [
    "https://core.internal.example/path",
    "https://core.internal.example?x=1",
    "https://core.internal.example#frag",
    "https://user:pass@core.internal.example",
    "file:///tmp/core",
    "javascript:alert(1)",
    " https://core.internal.example/path "
  ]) {
    await withEnv({ ...BFF_ENV, CORE_API_BASE_URL: raw }, () => {
      assert.equal(validatedCoreApiInternalBaseUrl(), null, raw);
    });
  }

  await withEnv(
    { ...BFF_ENV, ORDERPILOT_DEPLOY_PROFILE: "production", CORE_API_BASE_URL: "http://localhost:8080" },
    () => assert.equal(validatedCoreApiInternalBaseUrl(), null)
  );
  await withEnv(
    { ...BFF_ENV, ORDERPILOT_DEPLOY_PROFILE: "production", CORE_API_BASE_URL: "http://core.internal.test:8080" },
    () => assert.equal(validatedCoreApiInternalBaseUrl(), null)
  );
});

test("Core target URL preserves the exact validated path and query", async () => {
  await withEnv({ ...BFF_ENV, CORE_API_BASE_URL: "https://core.internal.example/" }, async () => {
    const sessionId = await operatorSession(["ANALYTICS_READ"]);
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/order-journeys?limit=10", { sessionId })
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "https://core.internal.example/api/v1/order-journeys?limit=10");
  });
});

test("strict numeric BFF config rejects malformed explicit values", async () => {
  assert.deepEqual(parseStrictBoundedInteger(undefined, "TEST_INT", { defaultValue: 7, min: 1, max: 10 }), {
    ok: true,
    value: 7
  });
  for (const raw of ["", "0", "-1", "+1", "1.5", "1e3", "300seconds", "999999999999999999999"]) {
    const parsed = parseStrictBoundedInteger(raw, "TEST_INT", { defaultValue: 7, min: 1, max: 10 });
    assert.equal(parsed.ok, false, raw);
  }
  await withEnv({ ...BFF_ENV, ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS: "30000abc" }, async () => {
    assert.throws(() => bffUpstreamTimeoutMs(), /ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS/);
    assert.match(await validateBffProductionConfig(), /ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS/);
  });
  await withEnv({ ...BFF_ENV, ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS: "300seconds" }, () => {
    assert.throws(() => bffGatewayClockSkewSeconds(), /ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS/);
  });
});
test("upstream timeout is bounded and maps to 504", async () => {
  await withEnv({ ...BFF_ENV, ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS: "1000" }, async () => {
    const sessionId = await operatorSession();
    const hangingFetch = (url, init) =>
      new Promise((_, reject) => {
        init.signal.addEventListener("abort", () =>
          reject(Object.assign(new Error("aborted"), { name: "AbortError" }))
        );
      });
    const response = await withFetch(hangingFetch, () =>
      proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(response.status, 504);
  });
});

test("registry contract: every registered rule is fully bound and clean of denied planes", () => {
  const denied = [
    "internal", "support", "staff", "admin", "ops", "diagnostics", "maintenance",
    "incident", "release", "break-glass", "webhook", "public", "demo"
  ];
  for (const rule of registeredBffRoutes()) {
    assert.equal(rule.plane, "tenant-operator");
    assert.ok(rule.permission.length > 0, `${rule.pattern} must bind a permission`);
    assert.ok(["read", "mutation"].includes(rule.kind));
    if (rule.kind === "mutation") {
      assert.equal(rule.csrfRequired, true, `${rule.pattern} mutation must require CSRF`);
      assert.equal(rule.contentType, "application/json");
      assert.ok(rule.maxBodyBytes > 0 && rule.maxBodyBytes <= 1024 * 1024);
      assert.notEqual(rule.method, "GET");
    } else {
      assert.equal(rule.method, "GET");
      assert.equal(rule.csrfRequired, false);
    }
    const segments = rule.pattern.split("/");
    for (const segment of segments) {
      assert.ok(
        !denied.includes(segment.toLowerCase()),
        `${rule.pattern} must not register a denied plane segment`
      );
    }
    assert.ok(!rule.pattern.includes("**"), "no broad prefix forwarding");
  }
  // broad prefixes must not match arbitrary sub-routes anymore
  assert.equal(matchBffRoute(["api", "v1", "quotes", "new-unknown-endpoint"], "POST"), null);
  assert.equal(matchBffRoute(["api", "stage8", "anything-new"], "GET"), null);
  assert.equal(matchBffRoute(["api", "v1", "quote-review", "issues"], "GET"), null);
  assert.equal(matchBffRoute(["api", "v1", "order-journeys", "by-source"], "GET")?.pattern, "api/v1/order-journeys/by-source");
  assert.equal(matchBffRoute(["api", "v1", "order-journeys", "not-a-uuid"], "GET"), null);
  assert.equal(matchBffRoute(["api", "v1", "order-journeys", "11111111-1111-4111-8111-111111111111"], "GET")?.pattern, "api/v1/order-journeys/:journeyId");
  assert.equal(matchBffRoute(["api", "v1", "quote-review", "conversion-attempts", "not-a-uuid"], "GET"), null);
});

test("invalid query is rejected with 400 and zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession(["ANALYTICS_READ"]);
    const { calls, impl } = recordingFetch();
    const unknown = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/order-journeys?unknown=1", { sessionId })
    );
    assert.equal(unknown.status, 400);
    const repeated = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/order-journeys?limit=1&limit=2", { sessionId })
    );
    assert.equal(repeated.status, 400);
    const malformed = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/order-journeys/by-source?sourceType=QUOTE&sourceId=not-a-uuid", { sessionId })
    );
    assert.equal(malformed.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("tracking-link and bot settings canonical methods are registered", () => {
  assert.equal(
    matchBffRoute(["api", "v1", "order-journeys", "11111111-1111-4111-8111-111111111111", "tracking-links"], "POST")?.permission,
    "REVIEW_ACTION"
  );
  assert.equal(
    matchBffRoute(["api", "v1", "order-journeys", "11111111-1111-4111-8111-111111111111", "tracking-links"], "GET")?.permission,
    "ANALYTICS_READ"
  );
  assert.equal(
    matchBffRoute(["api", "v1", "order-journeys", "11111111-1111-4111-8111-111111111111", "tracking-links", "22222222-2222-4222-8222-222222222222", "revoke"], "POST")?.permission,
    "REVIEW_ACTION"
  );
  assert.equal(matchBffRoute(["api", "v1", "bot-runtime", "settings"], "POST")?.permission, "BOT_ACTION");
  assert.equal(matchBffRoute(["api", "v1", "bot-runtime", "settings"], "PUT"), null);
  assert.equal(
    matchBffRoute(["api", "v1", "extractions", "11111111-1111-4111-8111-111111111111", "validation", "review-case"], "POST")?.permission,
    "EXTRACTION_RUN"
  );
  assert.equal(
    matchBffRoute(["api", "v1", "extractions", "11111111-1111-4111-8111-111111111111", "validation", "review-case"], "GET"),
    null
  );
  assert.equal(
    matchBffRoute(["api", "v1", "quotes", "drafts", "from-rfq-handoff", "11111111-1111-4111-8111-111111111111"], "POST")?.permission,
    "QUOTE_ACTION"
  );
  assert.equal(
    matchBffRoute(["api", "v1", "quotes", "drafts", "from-rfq-handoff", "11111111-1111-4111-8111-111111111111"], "GET"),
    null
  );
  assert.equal(
    matchBffRoute(["api", "v1", "ai-work", "rfq-handoffs", "11111111-1111-4111-8111-111111111111", "suggestions"], "POST")?.permission,
    "AI_WORK_ACTION"
  );
  assert.equal(
    matchBffRoute(["api", "v1", "ai-work", "rfq-handoffs", "11111111-1111-4111-8111-111111111111", "suggestions"], "GET"),
    null
  );
  assert.ok(
    matchBffRoute(["api", "v1", "workspace", "products", "search"], "GET")?.query.q
  );
  assert.ok(
    matchBffRoute(["api", "v1", "workspace", "draft-quotes", "review-queue"], "GET")?.query.status
  );
  assert.ok(
    matchBffRoute(["api", "v1", "channels", "rfq-handoffs"], "GET")?.query.status
  );
  assert.ok(
    matchBffRoute(["api", "v1", "ai-work", "suggestions"], "GET")?.query.limit
  );
});

test("upstream 4xx, non-json, oversized response and 204 are safely bounded", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const raw404 = await withFetch(
      recordingFetch(new Response("SQL tenant leak", { status: 404, headers: { "Content-Type": "application/json" } })).impl,
      () => proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(raw404.status, 404);
    assert.doesNotMatch(await raw404.text(), /SQL tenant leak/);

    const nonJson = await withFetch(
      recordingFetch(new Response("html", { status: 200, headers: { "Content-Type": "text/html" } })).impl,
      () => proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(nonJson.status, 502);

    const tooLarge = await withFetch(
      recordingFetch(new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json", "Content-Length": String(3 * 1024 * 1024) }
      })).impl,
      () => proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(tooLarge.status, 502);

    const noContent = await withFetch(
      recordingFetch(new Response(null, { status: 204 })).impl,
      () => proxied("/api/bff/api/v1/quote-review/queue", { sessionId })
    );
    assert.equal(noContent.status, 204);
    assert.equal(await noContent.text(), "");
  });
});

// ---------------------------------------------------------------------------
// F01 — Idempotency-Key must fail closed and share one grammar with Core.
// ---------------------------------------------------------------------------

const F01_MUTATION_PATH =
  "/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft";

async function f01Mutation(sessionId, impl, idempotencyKey) {
  return withFetch(impl, () =>
    proxied(F01_MUTATION_PATH, {
      sessionId,
      method: "POST",
      csrf: CSRF,
      body: JSON.stringify({ reasonCode: "READY" }),
      extraHeaders: idempotencyKey === undefined ? {} : { "Idempotency-Key": idempotencyKey }
    })
  );
}

test("F01: present-but-invalid Idempotency-Key fails closed (400) with zero upstream calls", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const invalidKeys = [
      "op~key~123", // historical BFF-valid / Core-invalid tilde character
      "bad key with spaces", // internal whitespace is preserved and rejected (never sanitized away)
      "op/key/slash",
      "key\twith\ttabs",
      "op*key*star", // disallowed punctuation, ByteString-safe
      "", // present but empty
      "x".repeat(IDEMPOTENCY_KEY_CONTRACT.maxLength + 1), // oversized
      "op-key-1,op-key-2" // duplicate / comma-collapsed ambiguous value
    ];
    const { calls, impl } = recordingFetch();
    for (const key of invalidKeys) {
      const response = await f01Mutation(sessionId, impl, key);
      assert.equal(response.status, 400, `invalid key ${JSON.stringify(key)} must be rejected`);
    }
    assert.equal(calls.length, 0, "no denied idempotency request reaches Core");
  });
});

test("F01: present + valid Idempotency-Key is forwarded byte-for-byte exactly once", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await f01Mutation(sessionId, impl, "op-key-123");
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.headers.get("Idempotency-Key"), "op-key-123");
  });
});

test("F01: optional policy — absent key is accepted and none is forwarded", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await f01Mutation(sessionId, impl, undefined);
    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].init.headers.get("Idempotency-Key"), null);
  });
});

test("F01: forbidden policy — a read route rejects a supplied Idempotency-Key (400, zero upstream)", async () => {
  await withEnv(BFF_ENV, async () => {
    const sessionId = await operatorSession();
    const { calls, impl } = recordingFetch();
    const response = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {
        sessionId,
        extraHeaders: { "Idempotency-Key": "op-key-123" }
      })
    );
    assert.equal(response.status, 400);
    assert.equal(calls.length, 0);
  });
});

test("F01: registry idempotency policy — reads forbid, mutations never forbid", () => {
  for (const rule of registeredBffRoutes()) {
    assert.ok(
      ["required", "optional", "forbidden"].includes(rule.idempotency),
      `${rule.method} ${rule.pattern} must declare an explicit idempotency policy`
    );
    if (rule.kind === "read") {
      assert.equal(rule.idempotency, "forbidden", `read ${rule.pattern} must forbid Idempotency-Key`);
    } else {
      assert.notEqual(rule.idempotency, "forbidden", `mutation ${rule.pattern} must accept Idempotency-Key`);
    }
  }
});

test("F01: resolver contract — required/optional/forbidden fail-closed matrix", () => {
  // required
  assert.deepEqual(resolveIdempotencyKey(null, "required"), { ok: false });
  assert.deepEqual(resolveIdempotencyKey("op-key-123", "required"), {
    ok: true,
    forward: true,
    value: "op-key-123"
  });
  assert.deepEqual(resolveIdempotencyKey("op~key", "required"), { ok: false });
  // optional
  assert.deepEqual(resolveIdempotencyKey(null, "optional"), { ok: true, forward: false });
  assert.deepEqual(resolveIdempotencyKey("op-key-123", "optional"), {
    ok: true,
    forward: true,
    value: "op-key-123"
  });
  assert.deepEqual(resolveIdempotencyKey("op~key", "optional"), { ok: false });
  // forbidden
  assert.deepEqual(resolveIdempotencyKey(null, "forbidden"), { ok: true, forward: false });
  assert.deepEqual(resolveIdempotencyKey("op-key-123", "forbidden"), { ok: false });
});

test("F01: canonical grammar excludes the historical tilde and accepts real key shapes", () => {
  assert.equal(isCanonicalIdempotencyKey("op~key"), false, "tilde is rejected (Core parity)");
  assert.equal(isCanonicalIdempotencyKey("op-key-123"), true);
  assert.equal(isCanonicalIdempotencyKey("rfq-handoff-decision-abc.def:1"), true);
  assert.equal(isCanonicalIdempotencyKey("SENTINEL_IDEMPOTENCY_KEY"), true);
  assert.equal(isCanonicalIdempotencyKey(""), false);
  assert.equal(isCanonicalIdempotencyKey("x".repeat(IDEMPOTENCY_KEY_CONTRACT.maxLength + 1)), false);
});

test("F01: BFF embedded contract equals the single-source JSON contract file", () => {
  const contractPath = fileURLToPath(
    new URL("../../../shared/contracts/idempotency-key-contract.json", import.meta.url)
  );
  const json = JSON.parse(readFileSync(contractPath, "utf8"));
  assert.equal(IDEMPOTENCY_KEY_CONTRACT.version, json.version);
  assert.equal(IDEMPOTENCY_KEY_CONTRACT.minLength, json.minLength);
  assert.equal(IDEMPOTENCY_KEY_CONTRACT.maxLength, json.maxLength);
  assert.equal(IDEMPOTENCY_KEY_CONTRACT.pattern, json.pattern);
  assert.equal(IDEMPOTENCY_KEY_CONTRACT.header, json.header);
});

// ---------------------------------------------------------------------------
// F05 — Query contract parity: zero-based page/offset valid, semantic numerics.
// ---------------------------------------------------------------------------

async function f05Read(path, permission, impl) {
  const sessionId = await operatorSession([permission]);
  return withFetch(impl, () => proxied(path, { sessionId }));
}

test("F05: RFQ handoff page is zero-based — page=0 valid, negative/size=0/overflow rejected", async () => {
  await withEnv(BFF_ENV, async () => {
    const base = "/api/bff/api/v1/channels/rfq-handoffs";
    const accepted = ["?page=0", "?page=1&size=20", "?size=1"];
    for (const q of accepted) {
      const { calls, impl } = recordingFetch();
      const res = await f05Read(base + q, "ADMIN_SETTINGS_READ", impl);
      assert.equal(res.status, 200, `accepted ${q}`);
      assert.equal(calls.length, 1, `forwarded ${q}`);
      assert.ok(calls[0].url.endsWith(q), `exact query preserved for ${q}`);
    }
    const rejected = ["?page=-1", "?size=0", "?page=1e3", "?page=1.0", "?page=+1", "?page=0x1", "?page=%201", "?page=1&page=2"];
    for (const q of rejected) {
      const { calls, impl } = recordingFetch();
      const res = await f05Read(base + q, "ADMIN_SETTINGS_READ", impl);
      assert.equal(res.status, 400, `rejected ${q}`);
      assert.equal(calls.length, 0, `no upstream for ${q}`);
    }
  });
});

test("F05: review-draft offset is zero-based numeric — offset=0 valid, nonnumeric/overflow rejected", async () => {
  await withEnv(BFF_ENV, async () => {
    const base = "/api/bff/api/v1/validations/review-drafts";
    const { calls: okCalls, impl: okImpl } = recordingFetch();
    const ok = await f05Read(base + "?offset=0", "VALIDATION_READ", okImpl);
    assert.equal(ok.status, 200);
    assert.equal(okCalls.length, 1);
    for (const q of ["?offset=abc", "?offset=1234567890", "?offset=-1", "?offset=1.5"]) {
      const { calls, impl } = recordingFetch();
      const res = await f05Read(base + q, "VALIDATION_READ", impl);
      assert.equal(res.status, 400, `rejected ${q}`);
      assert.equal(calls.length, 0, `no upstream for ${q}`);
    }
  });
});

// ---------------------------------------------------------------------------
// F04 — Cheap denials must not touch Redis; Redis failure stays fail-closed.
// ---------------------------------------------------------------------------

const PROD_ENV = {
  NODE_ENV: "test",
  ORDERPILOT_DEPLOY_PROFILE: "production",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_GATEWAY_SHARED_SECRET: "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517",
  ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard.test",
  CORE_API_BASE_URL: "http://127.0.0.1:8080",
  ORDERPILOT_BFF_REDIS_URL: "redis://localhost:6379"
};

const VALID_SYNTAX_SESSION = "T".repeat(43);

function trackingRedis({ connectThrows = false } = {}) {
  const counts = { connect: 0, get: 0 };
  const factory = () => ({
    isOpen: false,
    connect: async () => {
      counts.connect += 1;
      if (connectThrows) {
        throw new Error("ECONNREFUSED");
      }
    },
    get: async () => {
      counts.get += 1;
      return null;
    },
    setEx: async () => {},
    del: async () => {},
    on: () => {}
  });
  return { counts, factory };
}

test("F04: malformed path / unknown route / wrong method / bad cookie never touch Redis", async () => {
  await withEnv(PROD_ENV, async () => {
    const { counts, factory } = trackingRedis();
    setRedisClientFactoryForTesting(factory);
    const { calls, impl } = recordingFetch();
    const cheapDenials = [
      // malformed raw path (double slash) — rejected before route match
      { path: "/api/bff/api/v1/quote-review//queue", opts: { extraHeaders: { cookie: `op_session=${VALID_SYNTAX_SESSION}` } }, status: 400 },
      // unknown route — default deny 404
      { path: "/api/bff/api/v1/nonexistent/route", opts: { extraHeaders: { cookie: `op_session=${VALID_SYNTAX_SESSION}` } }, status: 404 },
      // wrong method on a GET-only route
      { path: "/api/bff/api/v1/quote-review/queue", opts: { method: "DELETE", extraHeaders: { cookie: `op_session=${VALID_SYNTAX_SESSION}` } }, status: 404 },
      // malformed session cookie syntax — rejected before Redis lookup
      { path: "/api/bff/api/v1/quote-review/queue", opts: { extraHeaders: { cookie: "op_session=short" } }, status: 401 },
      // missing cookie entirely
      { path: "/api/bff/api/v1/quote-review/queue", opts: {}, status: 401 }
    ];
    for (const { path, opts, status } of cheapDenials) {
      const res = await withFetch(impl, () => proxied(path, opts));
      assert.equal(res.status, status, `${path} -> ${status}`);
    }
    assert.equal(counts.connect, 0, "no cheap denial connected to Redis");
    assert.equal(counts.get, 0, "no cheap denial read a session from Redis");
    assert.equal(calls.length, 0, "no cheap denial reached Core");
    setRedisClientFactoryForTesting(null);
  });
});

test("F04: valid-syntax session with Redis down -> 401, one bounded connect, zero Core calls", async () => {
  await withEnv(PROD_ENV, async () => {
    const { counts, factory } = trackingRedis({ connectThrows: true });
    setRedisClientFactoryForTesting(factory);
    const { calls, impl } = recordingFetch();
    const res = await withFetch(impl, () =>
      proxied("/api/bff/api/v1/quote-review/queue", {
        extraHeaders: { cookie: `op_session=${VALID_SYNTAX_SESSION}` }
      })
    );
    assert.equal(res.status, 401, "Redis-down session lookup fails closed");
    assert.equal(calls.length, 0, "no Core call while Redis is down");
    assert.equal(counts.connect, 1, "exactly one bounded connect attempt");
    setRedisClientFactoryForTesting(null);
  });
});

test("F04: immutable config validation does not connect to Redis", async () => {
  await withEnv(PROD_ENV, async () => {
    const { counts, factory } = trackingRedis();
    setRedisClientFactoryForTesting(factory);
    assert.equal(validateBffProductionConfig !== undefined, true);
    // Importing the immutable check via the proxy module: a bad-config request returns 503
    // without any Redis contact. Force a numeric-config error and assert zero connects.
    await withEnv({ ...PROD_ENV, ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS: "not-a-number" }, async () => {
      setRedisClientFactoryForTesting(factory);
      const { calls, impl } = recordingFetch();
      const res = await withFetch(impl, () =>
        proxied("/api/bff/api/v1/quote-review/queue", {
          extraHeaders: { cookie: `op_session=${VALID_SYNTAX_SESSION}` }
        })
      );
      assert.equal(res.status, 503, "invalid immutable config -> 503");
      assert.equal(counts.connect, 0, "config validation never connected to Redis");
      assert.equal(calls.length, 0);
    });
    setRedisClientFactoryForTesting(null);
  });
});

// ---------------------------------------------------------------------------
// F02 — the browser upload path is fail-closed: no intake mutation/upload route
// is reachable through the generic BFF proxy (uploads are not registered).
// ---------------------------------------------------------------------------

test("F02: intake upload/mutation routes are not browser-reachable (404, zero Core calls)", async () => {
  await withEnv(BFF_ENV, async () => {
    // INTAKE_WRITE is not even a registered BFF permission, so a tenant session can never carry it.
    const sessionId = await operatorSession(["INTAKE_READ", "REVIEW_ACTION"]);
    const { calls, impl } = recordingFetch();
    const uploadAttempts = [
      { path: "/api/bff/api/v1/intake/documents/upload", method: "POST", contentType: "multipart/form-data; boundary=x" },
      { path: "/api/bff/api/v1/intake/documents", method: "POST", contentType: "application/json" },
      { path: "/api/bff/api/v1/intake/documents/api-upload", method: "POST", contentType: "application/json" }
    ];
    for (const { path, method, contentType } of uploadAttempts) {
      const response = await withFetch(impl, () =>
        proxied(path, {
          sessionId,
          method,
          csrf: CSRF,
          contentType,
          body: JSON.stringify({ contentBase64: "AAAA", originalFilename: "x.pdf", contentType: "application/pdf" })
        })
      );
      assert.equal(response.status, 404, `${method} ${path} must be default-denied`);
    }
    assert.equal(calls.length, 0, "no upload attempt reaches Core");
  });
});

test("F02: intake documents remain read-only through the BFF registry", () => {
  for (const rule of registeredBffRoutes()) {
    if (rule.pattern.startsWith("api/v1/intake")) {
      assert.equal(rule.kind, "read", `intake route ${rule.pattern} must be read-only in the tenant BFF`);
      assert.equal(rule.method, "GET");
    }
  }
});
