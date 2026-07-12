import assert from "node:assert/strict";
import test from "node:test";
import { handleLogout, handleSessionBootstrap } from "../lib/bff/bff-auth-handlers.ts";
import {
  loadOperatorSession,
  persistOperatorSession,
  requireRedisSessionBackend,
  resetSessionStoreForTesting,
  setRedisClientFactoryForTesting,
  InvalidSessionAuthorityError,
  SessionStoreUnavailableError
} from "../lib/bff/bff-session-store.ts";
import {
  isProductionNodeRuntime,
  isLocalTestBootstrapAllowed,
  isProductionLikeDeployment
} from "../lib/bff/bff-deployment-profile.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_PUBLIC_ORIGIN",
  "ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS",
  "ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID",
  "ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID",
  "ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS",
  "ORDERPILOT_BFF_REDIS_URL",
  "REDIS_URL",
  "ORDERPILOT_E2E_RUNTIME_NODE_ENV",
  // legacy keys must never restore production session HMAC behavior
  "ORDERPILOT_BFF_SESSION_SECRET",
  "ORDERPILOT_LOCAL_BOOTSTRAP_SECRET"
];

const VALID_TENANT_ID = "11111111-1111-4111-8111-111111111111";
const VALID_ACTOR_ID = "22222222-2222-4222-8222-222222222222";
const VALID_SESSION_ID = "S".repeat(43);

function nowEpoch() {
  return Math.floor(Date.now() / 1000);
}

function storedRecord(overrides = {}) {
  const issuedAtEpochSec = nowEpoch();
  return {
    sessionId: VALID_SESSION_ID,
    tenantId: VALID_TENANT_ID,
    actorId: VALID_ACTOR_ID,
    permissions: ["REVIEW_READ"],
    issuedAtEpochSec,
    expiresAtEpochSec: issuedAtEpochSec + 600,
    sessionVersion: 1,
    revoked: false,
    ...overrides
  };
}

const LOCAL_BOOTSTRAP_ENV = {
  NODE_ENV: "test",
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_PUBLIC_ORIGIN: "http://localhost:3000",
  ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "11111111-1111-4111-8111-111111111111",
  ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID: "22222222-2222-4222-8222-222222222222",
  ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: "REVIEW_READ,REVIEW_ACTION"
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

function bootstrapRequest(options = {}) {
  return new Request("http://localhost:3000/api/auth/session" + (options.query ?? ""), {
    method: "POST",
    headers: {
      origin: "http://localhost:3000",
      ...(options.headers ?? {})
    },
    body: options.body
  });
}

function setCookies(response) {
  return response.headers.getSetCookie();
}

function sessionIdFrom(response) {
  const cookie = setCookies(response).find((c) => c.startsWith("op_session="));
  return cookie?.split(";")[0].split("=")[1];
}

function csrfFrom(response) {
  const cookie = setCookies(response).find((c) => c.startsWith("op_csrf="));
  return cookie?.split(";")[0].split("=")[1];
}

function countingRedis() {
  const counts = { get: 0, setEx: 0, del: 0, connect: 0 };
  return {
    counts,
    client: {
      isOpen: true,
      connect: async () => {
        counts.connect += 1;
      },
      get: async () => {
        counts.get += 1;
        return null;
      },
      setEx: async () => {
        counts.setEx += 1;
      },
      del: async () => {
        counts.del += 1;
      },
      on: () => {}
    }
  };
}

test("runtimeNodeEnv is not compile-time inlined for security gates", async () => {
  const { readFileSync } = await import("node:fs");
  const { join } = await import("node:path");
  const source = readFileSync(join(process.cwd(), "lib/bff/bff-deployment-profile.ts"), "utf8");
  assert.match(source, /\["NODE", "ENV"\]\.join\("_"\)/);
  assert.doesNotMatch(source, /ORDERPILOT_E2E_RUNTIME_NODE_ENV/);
  assert.match(source, /export function isProductionNodeRuntime/);
  assert.doesNotMatch(source, /return process\.env\.NODE_ENV === "production"/);
  assert.doesNotMatch(source, /if \(process\.env\.NODE_ENV === "production"\)/);
});

test("NODE_ENV=production is always production-like regardless of deploy profile", async () => {
  for (const profile of ["", "production", "local", "test", "local-test", "unknown"]) {
    await withEnv({ NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: profile }, () => {
      assert.equal(isProductionNodeRuntime(), true, `profile=${profile}`);
      assert.equal(isProductionLikeDeployment(), true, `profile=${profile}`);
      assert.equal(isLocalTestBootstrapAllowed(), false, `profile=${profile}`);
    });
  }
});

test("former E2E runtime override cannot downgrade NODE_ENV=production", async () => {
  for (const override of ["test", "development", ""]) {
    await withEnv(
      {
        ...LOCAL_BOOTSTRAP_ENV,
        NODE_ENV: "production",
        ORDERPILOT_E2E_RUNTIME_NODE_ENV: override
      },
      () => {
        assert.equal(isProductionNodeRuntime(), true, `override=${override}`);
        assert.equal(isProductionLikeDeployment(), true, `override=${override}`);
        assert.equal(isLocalTestBootstrapAllowed(), false, `override=${override}`);
      }
    );
  }
});

test("non-production NODE_ENV respects explicit local/test and production-like profiles", async () => {
  await withEnv({ NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "local-test" }, () => {
    assert.equal(isProductionLikeDeployment(), false);
  });
  await withEnv({ NODE_ENV: "development", ORDERPILOT_DEPLOY_PROFILE: "local" }, () => {
    assert.equal(isProductionLikeDeployment(), false);
  });
  await withEnv({ NODE_ENV: "development", ORDERPILOT_DEPLOY_PROFILE: "production" }, () => {
    assert.equal(isProductionLikeDeployment(), true);
  });
  await withEnv({ NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "staging" }, () => {
    assert.equal(isProductionLikeDeployment(), true);
  });
  await withEnv({ NODE_ENV: "development" }, () => {
    assert.equal(isProductionLikeDeployment(), false);
  });
});

test("production Node runtime denies bootstrap for every profile and sets no cookies", async () => {
  const redis = countingRedis();
  for (const productionEnv of [
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "production" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "prod" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "cloud" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "staging" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "local" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "test" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "local-test" },
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "unknown" },
    {
      ...LOCAL_BOOTSTRAP_ENV,
      NODE_ENV: "production",
      ORDERPILOT_DEPLOY_PROFILE: "local-test",
      ORDERPILOT_BFF_SESSION_SECRET: "legacy-session-secret-must-not-enable-bootstrap-01234567",
      ORDERPILOT_LOCAL_BOOTSTRAP_SECRET: "legacy-local-secret-must-not-enable-bootstrap-01234567",
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    }
  ]) {
    await withEnv(productionEnv, async () => {
      setRedisClientFactoryForTesting(() => redis.client);
      const response = await handleSessionBootstrap(
        bootstrapRequest({
          headers: {
            origin: "http://localhost:3000",
            cookie: `op_session=${VALID_SESSION_ID}`,
            "X-Tenant-Id": "evil-tenant",
            "X-OrderPilot-Actor": "evil-actor",
            "X-OrderPilot-Permissions": "STAFF_SUPPORT_READ"
          }
        })
      );
      assert.equal(response.status, 404);
      assert.deepEqual(setCookies(response), [], "production denial must set zero cookies");
    });
  }
  assert.equal(redis.counts.get, 0, "production denial must not read Redis");
  assert.equal(redis.counts.setEx, 0, "production denial must not write Redis");
  assert.equal(redis.counts.del, 0, "production denial must not revoke Redis sessions");
  assert.equal(redis.counts.connect, 0, "production denial must not connect Redis");
});

test("production denial with valid body still returns 404 and sets no cookies", async () => {
  await withEnv(
    { ...LOCAL_BOOTSTRAP_ENV, NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: "local-test" },
    async () => {
      const response = await handleSessionBootstrap(
        bootstrapRequest({
          body: JSON.stringify({ tenantId: VALID_TENANT_ID }),
          headers: { origin: "http://localhost:3000", "Content-Type": "application/json" }
        })
      );
      assert.equal(response.status, 404);
      assert.deepEqual(setCookies(response), []);
    }
  );
});

test("bootstrap denied when the explicit local flag is missing", async () => {
  await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "false" }, async () => {
    const response = await handleSessionBootstrap(bootstrapRequest());
    assert.equal(response.status, 404);
    assert.deepEqual(setCookies(response), []);
  });
});

test("bootstrap denied without exact local origin", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(
      bootstrapRequest({ headers: { origin: "https://attacker.example" } })
    );
    assert.equal(response.status, 403);
    assert.deepEqual(setCookies(response), []);
  });
});

test("bootstrap denied without bounded predefined identity, no cookies set", async () => {
  await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "" }, async () => {
    const response = await handleSessionBootstrap(bootstrapRequest());
    assert.equal(response.status, 404);
    assert.deepEqual(setCookies(response), []);
  });
});

test("staff and support permissions in bootstrap identity are denied", async () => {
  for (const permissions of ["STAFF_SUPPORT_READ", "REVIEW_READ,STAFF_SUPPORT_READ", "ADMIN_SETTINGS_MANAGE"]) {
    await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: permissions }, async () => {
      const response = await handleSessionBootstrap(bootstrapRequest());
      assert.equal(response.status, 404);
      assert.deepEqual(setCookies(response), []);
    });
  }
});

test("duplicate or malformed bootstrap permissions are denied", async () => {
  for (const permissions of ["REVIEW_READ,REVIEW_READ", "review_read", "REVIEW_READ,"]) {
    await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: permissions }, async () => {
      const response = await handleSessionBootstrap(bootstrapRequest());
      assert.equal(response.status, 404);
      assert.deepEqual(setCookies(response), []);
    });
  }
});

test("request body cannot supply identity - any non-empty body is rejected without cookies", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(
      bootstrapRequest({
        body: JSON.stringify({ tenantId: "evil", roles: ["ADMIN"], permissions: ["STAFF_SUPPORT_READ"] }),
        headers: { "Content-Type": "application/json", origin: "http://localhost:3000" }
      })
    );
    assert.equal(response.status, 400);
    assert.deepEqual(setCookies(response), []);
  });
});

test("query string and headers cannot supply identity - env identity wins", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(
      bootstrapRequest({
        query: "?tenantId=evil&actorId=evil&permissions=STAFF_SUPPORT_READ",
        headers: {
          origin: "http://localhost:3000",
          "X-Tenant-Id": "evil-tenant",
          "X-OrderPilot-Permissions": "STAFF_SUPPORT_READ",
          "X-OrderPilot-Staff-Grant": "true"
        }
      })
    );
    assert.equal(response.status, 200);
    const record = await loadOperatorSession(sessionIdFrom(response));
    assert.equal(record.tenantId, LOCAL_BOOTSTRAP_ENV.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID);
    assert.equal(record.actorId, LOCAL_BOOTSTRAP_ENV.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID);
    assert.deepEqual(record.permissions, ["REVIEW_READ", "REVIEW_ACTION"]);
  });
});

test("local/test bootstrap issues an opaque HttpOnly session and CSRF cookie", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(bootstrapRequest());
    assert.equal(response.status, 200);
    const cookies = setCookies(response);
    assert.equal(cookies.length, 2);
    const sessionCookie = cookies.find((c) => c.startsWith("op_session="));
    assert.match(sessionCookie, /HttpOnly/);
    const sessionId = sessionIdFrom(response);
    assert.ok(sessionId.length >= 32, "opaque cryptographically random session id");
    assert.ok(!sessionId.includes(LOCAL_BOOTSTRAP_ENV.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID));
    const record = await loadOperatorSession(sessionId);
    assert.equal(record.sessionVersion, 1);
    assert.equal(record.revoked, false);
    assert.ok(record.issuedAtEpochSec > 0);
    assert.ok(record.expiresAtEpochSec > record.issuedAtEpochSec);
  });
});

test("valid tenant session persists and loads through the memory store", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const { sessionId, record } = await persistOperatorSession({
      tenantId: VALID_TENANT_ID,
      actorId: VALID_ACTOR_ID,
      permissions: ["REVIEW_READ", "REVIEW_ACTION"]
    });
    const loaded = await loadOperatorSession(sessionId);
    assert.deepEqual(loaded, record);
  });
});

const INVALID_AUTHORITY_CASES = [
  {
    name: "unknown syntactically valid permission",
    session: { tenantId: VALID_TENANT_ID, actorId: VALID_ACTOR_ID, permissions: ["UNKNOWN_PERMISSION"] }
  },
  {
    name: "staff/support permission",
    session: { tenantId: VALID_TENANT_ID, actorId: VALID_ACTOR_ID, permissions: ["STAFF_SUPPORT_READ"] }
  },
  {
    name: "malformed tenant UUID",
    session: { tenantId: "not-a-uuid", actorId: VALID_ACTOR_ID, permissions: ["REVIEW_READ"] }
  },
  {
    name: "malformed actor UUID",
    session: { tenantId: VALID_TENANT_ID, actorId: "not-a-uuid", permissions: ["REVIEW_READ"] }
  },
  {
    name: "duplicate permissions",
    session: { tenantId: VALID_TENANT_ID, actorId: VALID_ACTOR_ID, permissions: ["REVIEW_READ", "REVIEW_READ"] }
  },
  {
    name: "empty permission list",
    session: { tenantId: VALID_TENANT_ID, actorId: VALID_ACTOR_ID, permissions: [] }
  },
  {
    name: "permission count above limit",
    session: {
      tenantId: VALID_TENANT_ID,
      actorId: VALID_ACTOR_ID,
      permissions: Array.from({ length: 65 }, (_, index) => `UNKNOWN_${index}`)
    }
  }
];

for (const { name, session } of INVALID_AUTHORITY_CASES) {
  test(`invalid session authority is rejected before memory storage mutation: ${name}`, async () => {
    await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
      await assert.rejects(() => persistOperatorSession(session), InvalidSessionAuthorityError);
    });
  });
}

test("expired session issuance is rejected before storage write", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    await assert.rejects(
      () =>
        persistOperatorSession({
          tenantId: VALID_TENANT_ID,
          actorId: VALID_ACTOR_ID,
          permissions: ["REVIEW_READ"],
          expiresAtEpochSec: nowEpoch() - 1
        }),
      InvalidSessionAuthorityError
    );
  });
});

test("excessive custom expiry is rejected", async () => {
  await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS: "300" }, async () => {
    await assert.rejects(
      () =>
        persistOperatorSession({
          tenantId: VALID_TENANT_ID,
          actorId: VALID_ACTOR_ID,
          permissions: ["REVIEW_READ"],
          expiresAtEpochSec: nowEpoch() + 3600
        }),
      InvalidSessionAuthorityError
    );
  });
});

test("invalid Redis-backed issuance does not call setEx", async () => {
  let setExCalls = 0;
  const redis = {
    isOpen: true,
    connect: async () => {},
    get: async () => null,
    setEx: async () => {
      setExCalls += 1;
    },
    del: async () => {},
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => redis);
      await assert.rejects(
        () =>
          persistOperatorSession({
            tenantId: VALID_TENANT_ID,
            actorId: VALID_ACTOR_ID,
            permissions: ["STAFF_SUPPORT_READ"]
          }),
        InvalidSessionAuthorityError
      );
      assert.equal(setExCalls, 0);
    }
  );
});

test("re-bootstrap rotates the session: the old ID is invalidated", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const first = await handleSessionBootstrap(bootstrapRequest());
    const firstId = sessionIdFrom(first);
    assert.ok(await loadOperatorSession(firstId));
    const second = await handleSessionBootstrap(
      bootstrapRequest({ headers: { origin: "http://localhost:3000", cookie: `op_session=${firstId}` } })
    );
    const secondId = sessionIdFrom(second);
    assert.notEqual(firstId, secondId);
    assert.equal(await loadOperatorSession(firstId), null, "rotation must invalidate the old ID");
    assert.ok(await loadOperatorSession(secondId));
  });
});

test("expired stored Redis records fail closed", async () => {
  const expiredRecord = storedRecord({
    issuedAtEpochSec: nowEpoch() - 120,
    expiresAtEpochSec: nowEpoch() - 60
  });
  const redisWithExpired = {
    isOpen: true,
    connect: async () => {},
    get: async () => JSON.stringify(expiredRecord),
    setEx: async () => {},
    del: async () => {},
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => redisWithExpired);
      assert.equal(await loadOperatorSession(VALID_SESSION_ID), null);
    }
  );
});

test("missing sessions fail closed", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    assert.equal(await loadOperatorSession("nonexistent-session-id"), null);
    assert.equal(await loadOperatorSession(undefined), null);
    assert.equal(await loadOperatorSession("  "), null);
  });
});

test("logout revokes the server-side session before clearing cookies; old cookie reuse fails", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const signIn = await handleSessionBootstrap(bootstrapRequest());
    const sessionId = sessionIdFrom(signIn);
    const csrf = csrfFrom(signIn);
    const logout = await handleLogout(
      new Request("http://localhost:3000/api/auth/logout", {
        method: "POST",
        headers: {
          host: "localhost:3000",
          origin: "http://localhost:3000",
          cookie: `op_session=${sessionId}; op_csrf=${csrf}`,
          "X-OP-CSRF-Token": csrf
        }
      })
    );
    assert.equal(logout.status, 200);
    assert.equal(await loadOperatorSession(sessionId), null, "reusing the old cookie must fail");
    const cleared = setCookies(logout);
    assert.ok(cleared.some((c) => c.startsWith("op_session=;")));
  });
});

test("logout without CSRF is denied and revokes nothing", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const signIn = await handleSessionBootstrap(bootstrapRequest());
    const sessionId = sessionIdFrom(signIn);
    const logout = await handleLogout(
      new Request("http://localhost:3000/api/auth/logout", {
        method: "POST",
        headers: {
          host: "localhost:3000",
          origin: "http://localhost:3000",
          cookie: `op_session=${sessionId}`
        }
      })
    );
    assert.equal(logout.status, 403);
    assert.deepEqual(setCookies(logout), []);
    assert.ok(await loadOperatorSession(sessionId), "denied logout must not revoke the session");
  });
});

test("invalid bootstrap security cookie denies without rotation or new cookies", async () => {
  for (const cookie of [
    `op_session=${VALID_SESSION_ID}; op_session=${VALID_SESSION_ID}`,
    `op_session=${VALID_SESSION_ID}; op_session=${"T".repeat(43)}`,
    "op_session=",
    "op_session=%E0%A4%A",
    "op_session=short"
  ]) {
    await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
      const signIn = await handleSessionBootstrap(bootstrapRequest());
      const existingSessionId = sessionIdFrom(signIn);
      const response = await handleSessionBootstrap(
        bootstrapRequest({ headers: { origin: "http://localhost:3000", cookie } })
      );
      assert.equal(response.status, 403, cookie);
      assert.deepEqual(setCookies(response), [], "invalid bootstrap cookie must not set cookies");
      assert.ok(await loadOperatorSession(existingSessionId), "invalid bootstrap must not revoke existing state");
    });
  }
});

test("invalid logout security cookie denies without revocation or cookie clearing", async () => {
  for (const cookieBuilder of [
    (sessionId, csrf) => `op_session=${sessionId}; op_session=${sessionId}; op_csrf=${csrf}`,
    (sessionId, csrf) => `op_session=${sessionId}; op_csrf=${csrf}; op_csrf=${csrf}`,
    (_sessionId, csrf) => `op_session=; op_csrf=${csrf}`,
    (_sessionId, csrf) => `op_session=%E0%A4%A; op_csrf=${csrf}`,
    (sessionId, _csrf) => `op_session=${sessionId}; op_csrf=`
  ]) {
    await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
      const signIn = await handleSessionBootstrap(bootstrapRequest());
      const sessionId = sessionIdFrom(signIn);
      const csrf = csrfFrom(signIn);
      const response = await handleLogout(
        new Request("http://localhost:3000/api/auth/logout", {
          method: "POST",
          headers: {
            host: "localhost:3000",
            origin: "http://localhost:3000",
            cookie: cookieBuilder(sessionId, csrf),
            "X-OP-CSRF-Token": csrf
          }
        })
      );
      assert.equal(response.status, 403);
      assert.deepEqual(setCookies(response), [], "denied logout must not clear cookies");
      assert.ok(await loadOperatorSession(sessionId), "denied logout must not revoke the session");
    });
  }
});
test("Redis is mandatory for production-like sessions - no memory fallback", async () => {
  await withEnv(
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "production" },
    async () => {
      await assert.rejects(
        persistOperatorSession({ tenantId: VALID_TENANT_ID, actorId: VALID_ACTOR_ID, permissions: ["REVIEW_READ"] }),
        SessionStoreUnavailableError
      );
      const error = await requireRedisSessionBackend();
      assert.match(error, /ORDERPILOT_BFF_REDIS_URL/);
    }
  );
});

test("Redis command errors fail closed on load and deny bootstrap without cookies", async () => {
  const failingRedis = {
    isOpen: true,
    connect: async () => {},
    get: async () => {
      throw new Error("redis down");
    },
    setEx: async () => {
      throw new Error("redis down");
    },
    del: async () => {
      throw new Error("redis down");
    },
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => failingRedis);
      assert.equal(await loadOperatorSession(VALID_SESSION_ID), null, "Redis errors fail closed");
      const response = await handleSessionBootstrap(bootstrapRequest());
      assert.equal(response.status, 503);
      assert.deepEqual(setCookies(response), [], "no cookies on store failure");
    }
  );
});

test("tampered Redis record with unknown permission fails closed", async () => {
  const redisWithTamperedRecord = {
    isOpen: true,
    connect: async () => {},
    get: async () => JSON.stringify(storedRecord({ permissions: ["INTERNAL_ADMIN"] })),
    setEx: async () => {},
    del: async () => {},
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => redisWithTamperedRecord);
      assert.equal(await loadOperatorSession(VALID_SESSION_ID), null);
    }
  );
});

test("tampered Redis record with invalid UUID fails closed", async () => {
  const redisWithTamperedRecord = {
    isOpen: true,
    connect: async () => {},
    get: async () => JSON.stringify(storedRecord({ tenantId: "not-a-uuid" })),
    setEx: async () => {},
    del: async () => {},
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => redisWithTamperedRecord);
      assert.equal(await loadOperatorSession(VALID_SESSION_ID), null);
    }
  );
});

test("revoked session records fail closed", async () => {
  const redisWithRevoked = {
    isOpen: true,
    connect: async () => {},
    get: async () => JSON.stringify(storedRecord({ revoked: true })),
    setEx: async () => {},
    del: async () => {},
    on: () => {}
  };
  await withEnv(
    {
      ...LOCAL_BOOTSTRAP_ENV,
      ORDERPILOT_BFF_SESSION_STORE: "",
      ORDERPILOT_BFF_REDIS_URL: "redis://localhost:63790"
    },
    async () => {
      setRedisClientFactoryForTesting(() => redisWithRevoked);
      assert.equal(await loadOperatorSession(VALID_SESSION_ID), null);
    }
  );
});
