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

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_LOCAL_BOOTSTRAP_SECRET",
  "ORDERPILOT_BFF_SESSION_SECRET",
  "ORDERPILOT_PUBLIC_ORIGIN",
  "ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS",
  "ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID",
  "ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID",
  "ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS",
  "ORDERPILOT_BFF_REDIS_URL",
  "REDIS_URL"
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
  ORDERPILOT_DEPLOY_PROFILE: "local-test",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
  ORDERPILOT_BFF_SESSION_STORE: "memory",
  ORDERPILOT_LOCAL_BOOTSTRAP_SECRET: "p1b-local-bootstrap-secret-test-only-0123456789ab",
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
    headers: options.headers ?? {},
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

test("production-like deployments deny bootstrap and set no cookies", async () => {
  for (const productionEnv of [
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "production" },
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "prod" },
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "cloud" },
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "staging" },
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "", NODE_ENV: "production" }
  ]) {
    await withEnv(productionEnv, async () => {
      const response = await handleSessionBootstrap(bootstrapRequest());
      assert.equal(response.status, 503);
      assert.deepEqual(setCookies(response), [], "failed authentication must set no cookies");
    });
  }
});

test("bootstrap denied when the explicit local flag is missing", async () => {
  await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "false" }, async () => {
    const response = await handleSessionBootstrap(bootstrapRequest());
    assert.equal(response.status, 503);
    assert.deepEqual(setCookies(response), []);
  });
});

test("bootstrap denied without bounded predefined identity, no cookies set", async () => {
  await withEnv({ ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "" }, async () => {
    const response = await handleSessionBootstrap(bootstrapRequest());
    assert.equal(response.status, 503);
    assert.deepEqual(setCookies(response), []);
  });
});

test("request body cannot supply identity — any non-empty body is rejected without cookies", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(
      bootstrapRequest({
        body: JSON.stringify({ tenantId: "evil", roles: ["ADMIN"], permissions: ["STAFF_SUPPORT_READ"] }),
        headers: { "Content-Type": "application/json" }
      })
    );
    assert.equal(response.status, 400);
    assert.deepEqual(setCookies(response), []);
  });
});

test("query string and headers cannot supply identity — env identity wins", async () => {
  await withEnv(LOCAL_BOOTSTRAP_ENV, async () => {
    const response = await handleSessionBootstrap(
      bootstrapRequest({
        query: "?tenantId=evil&actorId=evil&permissions=STAFF_SUPPORT_READ",
        headers: {
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
      bootstrapRequest({ headers: { cookie: `op_session=${firstId}` } })
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

test("Redis is mandatory for production-like sessions — no memory fallback", async () => {
  await withEnv(
    { ...LOCAL_BOOTSTRAP_ENV, ORDERPILOT_DEPLOY_PROFILE: "production" },
    async () => {
      // even with ORDERPILOT_BFF_SESSION_STORE=memory set, production-like refuses memory
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
