import assert from "node:assert/strict";
import test from "node:test";
import { proxyCoreRequest, rawBffPathRejected, validateBffProductionConfig } from "../lib/bff/bff-proxy.ts";
import {
  matchBffRoute,
  registeredBffRoutes
} from "../lib/bff/bff-route-registry.ts";
import {
  persistOperatorSession,
  resetSessionStoreForTesting
} from "../lib/bff/bff-session-store.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_BFF_SESSION_SECRET",
  "ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET",
  "CORE_API_BASE_URL",
  "ORDERPILOT_BFF_REDIS_URL",
  "REDIS_URL",
  "ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS"
];

const BFF_ENV = {
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_BFF_SESSION_SECRET: "x".repeat(48),
  ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET: "gateway-test-only-secret",
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
    assert.equal(upstreamHeaders.get("X-OrderPilot-Permissions"), "REVIEW_READ,REVIEW_ACTION");
    assert.ok(upstreamHeaders.get("X-OrderPilot-Gateway-Signature"));
    assert.ok(upstreamHeaders.get("X-OrderPilot-Gateway-Nonce"));
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
