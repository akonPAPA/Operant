import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  deniedProjection,
  offerFilterCapabilities,
  parseUiCapabilityProjection,
  projectPermissionsToUiCapabilities,
  projectionFromPermissions,
  unavailableProjection
} from "../lib/ui-capability-model.ts";
import { handleUiCapabilityProjection } from "../lib/bff/bff-ui-capability-handlers.ts";
import {
  persistOperatorSession,
  resetSessionStoreForTesting,
  setRedisClientFactoryForTesting,
  expireOperatorSessionForTesting
} from "../lib/bff/bff-session-store.ts";
import {
  sessionCookieHeaderForCapabilityFetch,
  trustedCapabilityFetchOrigin,
  UI_CAPABILITY_RESPONSE_MAX_BYTES
} from "../lib/server/ui-capability-fetch-policy.server.ts";
import { tenantPrimaryDestinations } from "../components/navigation-registry.ts";
import { BFF_SESSION_COOKIE } from "../lib/bff/bff-config.ts";

const VALID_TENANT = "11111111-1111-4111-8111-111111111111";
const VALID_ACTOR = "22222222-2222-4222-8222-222222222222";
const ROOT = dirname(fileURLToPath(import.meta.url));

const MEMORY_ENV = {
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  NODE_ENV: "test",
  ORDERPILOT_PUBLIC_ORIGIN: "http://localhost:3100"
};

const REDIS_ENV = {
  ORDERPILOT_BFF_SESSION_STORE: "",
  ORDERPILOT_DEPLOY_PROFILE: "production",
  NODE_ENV: "production",
  ORDERPILOT_BFF_REDIS_HOST: "127.0.0.1",
  ORDERPILOT_BFF_REDIS_PORT: "6379",
  ORDERPILOT_BFF_REDIS_PASSWORD: "test-redis-password-not-a-secret-value",
  ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard.test"
};

async function withEnv(env, fn) {
  const previous = {};
  for (const key of Object.keys(env)) {
    previous[key] = process.env[key];
    const value = env[key];
    if (value === undefined || value === "") delete process.env[key];
    else process.env[key] = value;
  }
  try {
    return await fn();
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    resetSessionStoreForTesting();
  }
}

function fakeRedis(store = new Map()) {
  return {
    isOpen: true,
    connect: async () => {},
    get: async (key) => (store.has(key) ? store.get(key) : null),
    setEx: async (key, _ttl, value) => {
      store.set(key, value);
    },
    del: async (key) => {
      store.delete(key);
    },
    on: () => {}
  };
}

test("unknown backend permissions grant no UI capability", () => {
  const caps = projectPermissionsToUiCapabilities(["BOT_READ", "NOT_A_REAL_PERMISSION", "CONTROL_READ"]);
  assert.equal(caps.size, 0);
});

test("staff and support permissions never map into tenant UI capabilities", () => {
  const caps = projectPermissionsToUiCapabilities([
    "STAFF_SUPPORT_READ",
    "SUPPORT_GRANT_READ",
    "ADMIN_TENANT_READ",
    "INTERNAL_CONTROL_READ"
  ]);
  assert.equal(caps.size, 0);
});

test("known permissions map to allowlisted UI capabilities only", () => {
  const caps = projectPermissionsToUiCapabilities(["ANALYTICS_READ", "REVIEW_READ", "REVIEW_ACTION", "INTAKE_READ"]);
  assert.deepEqual([...caps].sort(), [
    "PERFORM_REVIEW_ACTION",
    "VIEW_ANALYTICS",
    "VIEW_DOCUMENTS",
    "VIEW_REVIEW_QUEUE"
  ]);
});

test("allowed projection offers gated destinations; denied/unavailable do not", () => {
  const allowed = offerFilterCapabilities(projectionFromPermissions(["ANALYTICS_READ", "REVIEW_READ"]));
  const paths = tenantPrimaryDestinations(allowed).map((dest) => dest.path);
  assert.equal(paths.includes("/analytics"), true);
  assert.equal(paths.includes("/quote-review"), true);
  assert.equal(paths.includes("/command-center"), true);

  const deniedPaths = tenantPrimaryDestinations(offerFilterCapabilities(deniedProjection())).map((d) => d.path);
  assert.equal(deniedPaths.includes("/analytics"), false);
  assert.equal(deniedPaths.includes("/quote-review"), false);
  assert.equal(deniedPaths.includes("/command-center"), true, "universally safe routes remain");

  const unavailablePaths = tenantPrimaryDestinations(
    offerFilterCapabilities(unavailableProjection())
  ).map((d) => d.path);
  assert.equal(unavailablePaths.includes("/analytics"), false);
  assert.equal(unavailablePaths.includes("/command-center"), true);
});

test("tenant primary destinations never include staff/customer/service/internal planes", () => {
  const paths = tenantPrimaryDestinations(
    offerFilterCapabilities(projectionFromPermissions(["ANALYTICS_READ", "REVIEW_READ", "SETTINGS_READ"]))
  ).map((d) => d.path);
  assert.equal(paths.includes("/internal-support"), false);
  assert.equal(paths.includes("/internal-support/operations"), false);
  assert.equal(paths.includes("/public/order-tracking"), false);
});

test("parseUiCapabilityProjection rejects malformed and unknown capability strings", () => {
  assert.equal(parseUiCapabilityProjection(null), null);
  assert.equal(parseUiCapabilityProjection({ status: "ALLOWED" }), null);
  assert.equal(parseUiCapabilityProjection({ status: "ALLOWED", capabilities: ["NOT_A_CAP"] }), null);
  assert.equal(parseUiCapabilityProjection({ status: "DENIED", capabilities: ["VIEW_ANALYTICS"] }), null);
  assert.equal(
    parseUiCapabilityProjection({
      status: "ALLOWED",
      capabilities: ["VIEW_ANALYTICS"],
      tenantId: VALID_TENANT
    }),
    null
  );
  assert.deepEqual(parseUiCapabilityProjection({ status: "ALLOWED", capabilities: ["VIEW_ANALYTICS"] }), {
    status: "ALLOWED",
    capabilities: ["VIEW_ANALYTICS"]
  });
});

test("API handler projects session permissions without leaking authority fields", async () => {
  await withEnv(MEMORY_ENV, async () => {
    const { sessionId } = await persistOperatorSession({
      tenantId: VALID_TENANT,
      actorId: VALID_ACTOR,
      permissions: ["ANALYTICS_READ", "REVIEW_READ"]
    });
    const response = await handleUiCapabilityProjection(
      new Request("http://localhost/api/ui/capabilities", {
        headers: { cookie: `op_session=${sessionId}` }
      })
    );
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Cache-Control"), "private, no-store");
    assert.match(response.headers.get("Content-Type") ?? "", /application\/json/);
    const body = await response.json();
    assert.equal(body.status, "ALLOWED");
    assert.deepEqual(body.capabilities.sort(), ["VIEW_ANALYTICS", "VIEW_REVIEW_QUEUE"]);
    assert.equal("tenantId" in body, false);
    assert.equal("actorId" in body, false);
    assert.equal("permissions" in body, false);
    assert.equal("role" in body, false);
    assert.equal(JSON.stringify(body).includes(VALID_TENANT), false);
    assert.equal(JSON.stringify(body).includes(VALID_ACTOR), false);
    assert.equal(JSON.stringify(body).includes("ANALYTICS_READ"), false);
    assert.equal(JSON.stringify(body).includes(sessionId), false);
  });
});

test("API handler denies missing and expired sessions without leaking internals", async () => {
  await withEnv(MEMORY_ENV, async () => {
    const missing = await handleUiCapabilityProjection(new Request("http://localhost/api/ui/capabilities"));
    assert.equal(missing.status, 401);
    assert.deepEqual(await missing.json(), { status: "DENIED", capabilities: [] });

    const { sessionId } = await persistOperatorSession({
      tenantId: VALID_TENANT,
      actorId: VALID_ACTOR,
      permissions: ["ANALYTICS_READ"]
    });
    expireOperatorSessionForTesting(sessionId);
    const expired = await handleUiCapabilityProjection(
      new Request("http://localhost/api/ui/capabilities", {
        headers: { cookie: `op_session=${sessionId}` }
      })
    );
    assert.equal(expired.status, 401);
    assert.deepEqual(await expired.json(), { status: "DENIED", capabilities: [] });
  });
});

test("local-test force-unavailable flag returns UNAVAILABLE; production Node ignores it", async () => {
  await withEnv(
    {
      ...MEMORY_ENV,
      ORDERPILOT_BFF_ENABLED: "true",
      ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
      ORDERPILOT_BFF_FORCE_CAPABILITY_UNAVAILABLE: "true"
    },
    async () => {
      const { sessionId } = await persistOperatorSession({
        tenantId: VALID_TENANT,
        actorId: VALID_ACTOR,
        permissions: ["ANALYTICS_READ"]
      });
      const forced = await handleUiCapabilityProjection(
        new Request("http://localhost/api/ui/capabilities", {
          headers: { cookie: `op_session=${sessionId}` }
        })
      );
      assert.equal(forced.status, 503);
      assert.deepEqual(await forced.json(), { status: "UNAVAILABLE", capabilities: [] });
    }
  );

  await withEnv(
    {
      NODE_ENV: "production",
      ORDERPILOT_DEPLOY_PROFILE: "production",
      ORDERPILOT_BFF_ENABLED: "true",
      ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
      ORDERPILOT_BFF_FORCE_CAPABILITY_UNAVAILABLE: "true",
      ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard.test",
      ORDERPILOT_BFF_SESSION_STORE: ""
    },
    async () => {
      // Production Node runtime must ignore the force flag.
      const response = await handleUiCapabilityProjection(new Request("http://localhost/api/ui/capabilities"));
      assert.equal(response.status, 401);
      assert.deepEqual(await response.json(), { status: "DENIED", capabilities: [] });
    }
  );
});

test("unsupported methods are denied safely", async () => {
  const response = await handleUiCapabilityProjection(
    new Request("http://localhost/api/ui/capabilities", { method: "POST" })
  );
  assert.equal(response.status, 405);
  assert.deepEqual(await response.json(), { status: "DENIED", capabilities: [] });
});

test("two sessions never observe each other's capability projection", async () => {
  await withEnv(MEMORY_ENV, async () => {
    const sessionA = await persistOperatorSession({
      tenantId: VALID_TENANT,
      actorId: VALID_ACTOR,
      permissions: ["ANALYTICS_READ"]
    });
    const sessionB = await persistOperatorSession({
      tenantId: "33333333-3333-4333-8333-333333333333",
      actorId: "44444444-4444-4444-8444-444444444444",
      permissions: ["REVIEW_READ"]
    });
    const responseA = await handleUiCapabilityProjection(
      new Request("http://localhost/api/ui/capabilities", {
        headers: { cookie: `op_session=${sessionA.sessionId}` }
      })
    );
    const responseB = await handleUiCapabilityProjection(
      new Request("http://localhost/api/ui/capabilities", {
        headers: { cookie: `op_session=${sessionB.sessionId}` }
      })
    );
    const bodyA = await responseA.json();
    const bodyB = await responseB.json();
    assert.deepEqual(bodyA.capabilities, ["VIEW_ANALYTICS"]);
    assert.deepEqual(bodyB.capabilities, ["VIEW_REVIEW_QUEUE"]);
    assert.equal(bodyB.capabilities.includes("VIEW_ANALYTICS"), false);
  });
});

test("Redis-backed session resolves the same allowlisted projection as memory", async () => {
  const store = new Map();
  await withEnv(REDIS_ENV, async () => {
    setRedisClientFactoryForTesting(() => fakeRedis(store));
    const { sessionId } = await persistOperatorSession({
      tenantId: VALID_TENANT,
      actorId: VALID_ACTOR,
      permissions: ["ANALYTICS_READ", "REVIEW_READ"]
    });
    const response = await handleUiCapabilityProjection(
      new Request("http://localhost/api/ui/capabilities", {
        headers: { cookie: `op_session=${sessionId}` }
      })
    );
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.status, "ALLOWED");
    assert.deepEqual(body.capabilities.sort(), ["VIEW_ANALYTICS", "VIEW_REVIEW_QUEUE"]);
    assert.equal("permissions" in body, false);
    assert.equal(JSON.stringify(body).includes(VALID_TENANT), false);
  });
});

test("session cookie projection forwards only op_session", () => {
  const mixed =
    "op_csrf=csrf-token-value-0123456789; op_session=abcdefghijklmnopqrstuvwxABCDEFGHIJKLMNOPQRSTUV; unrelated=secret";
  const projected = sessionCookieHeaderForCapabilityFetch(mixed);
  assert.equal(projected, `${BFF_SESSION_COOKIE}=abcdefghijklmnopqrstuvwxABCDEFGHIJKLMNOPQRSTUV`);
  assert.equal(projected?.includes("op_csrf"), false);
  assert.equal(projected?.includes("unrelated"), false);
  assert.equal(projected?.includes("secret"), false);
});

test("trusted capability origin never comes from Host headers", async () => {
  await withEnv(MEMORY_ENV, async () => {
    assert.equal(trustedCapabilityFetchOrigin(), "http://localhost:3100");
  });
  await withEnv({ ...MEMORY_ENV, ORDERPILOT_PUBLIC_ORIGIN: "" }, async () => {
    assert.equal(trustedCapabilityFetchOrigin(), null);
  });
  const source = readFileSync(join(ROOT, "../lib/server/load-ui-capability-projection.server.ts"), "utf8");
  assert.doesNotMatch(source, /x-forwarded-host/);
  assert.doesNotMatch(source, /get\(["']host["']\)/);
  assert.doesNotMatch(source, /get\(["']forwarded["']\)/);
  assert.doesNotMatch(source, /get\(["']origin["']\)/);
  assert.doesNotMatch(source, /get\(["']referer["']\)/);
  assert.match(source, /bffPublicOrigin|trustedCapabilityFetchOrigin/);
  assert.match(source, /cache:\s*["']no-store["']/);
  assert.match(source, /sessionCookieHeaderForCapabilityFetch/);
  assert.ok(UI_CAPABILITY_RESPONSE_MAX_BYTES >= 1024);
});

test("RSC loader never statically imports session store (split-instance guard)", () => {
  const source = readFileSync(join(ROOT, "../lib/server/load-ui-capability-projection.server.ts"), "utf8");
  assert.doesNotMatch(source, /from ["'].*bff-session-store/);
  assert.doesNotMatch(source, /\bloadOperatorSession\s*\(/);
  assert.match(source, /\/api\/ui\/capabilities/);
  assert.match(source, /server-only/);
  assert.match(source, /\bcache\b/);
});

test("dashboard shell loads projection server-side and does not import session store", () => {
  const source = readFileSync(join(ROOT, "../components/dashboard-shell.tsx"), "utf8");
  assert.match(source, /loadUiCapabilityProjection/);
  assert.doesNotMatch(source, /from ["'].*bff-session-store/);
  assert.doesNotMatch(source, /\bloadOperatorSession\s*\(/);
  assert.match(source, /offerFilterCapabilities/);
});

test("capability route is force-dynamic with private no-store handler contract", () => {
  const route = readFileSync(join(ROOT, "../app/api/ui/capabilities/route.ts"), "utf8");
  const handler = readFileSync(join(ROOT, "../lib/bff/bff-ui-capability-handlers.ts"), "utf8");
  assert.match(route, /force-dynamic/);
  assert.match(route, /runtime\s*=\s*["']nodejs["']/);
  assert.match(handler, /private, no-store/);
  assert.match(handler, /Content-Type.*application\/json/);
});
