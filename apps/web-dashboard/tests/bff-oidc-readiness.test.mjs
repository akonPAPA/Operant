import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD_FLAG = "ORDERPILOT_OIDC_READINESS_CHILD";

if (process.env[CHILD_FLAG] !== "1") {
  test("P1-C readiness passes under server-only condition", () => {
    const result = spawnSync(process.execPath, ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)], {
      cwd: process.cwd(),
      env: { ...process.env, [CHILD_FLAG]: "1" },
      encoding: "utf8"
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const { validateOidcProductionReadiness } = await import("../lib/bff/bff-oidc-readiness.ts");
  const { createOidcRuntimeCache } = await import("../lib/bff/bff-oidc-runtime.ts");
  const { ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV } = await import("../lib/bff/bff-oidc-identity-mapping.ts");
  const ENV_KEYS = ["NODE_ENV", "ORDERPILOT_DEPLOY_PROFILE", "ORDERPILOT_BFF_ENABLED", "ORDERPILOT_DEMO_MODE", "ORDERPILOT_BFF_SESSION_STORE", "ORDERPILOT_PUBLIC_ORIGIN", "CORE_API_BASE_URL", "ORDERPILOT_GATEWAY_SHARED_SECRET", "ORDERPILOT_OIDC_ENABLED", "ORDERPILOT_OIDC_ISSUER", "ORDERPILOT_OIDC_CLIENT_ID", "ORDERPILOT_OIDC_CLIENT_SECRET", "ORDERPILOT_OIDC_REDIRECT_URI", "ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI", "ORDERPILOT_OIDC_SCOPES", "ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD", ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV];
  const ISSUER = "https://idp.example.test/tenant-a";
  const PUBLIC = "https://dashboard.example.test";
  const BASE = {
    NODE_ENV: "test",
    ORDERPILOT_DEPLOY_PROFILE: "local-test",
    ORDERPILOT_BFF_ENABLED: "true",
    ORDERPILOT_DEMO_MODE: "false",
    ORDERPILOT_BFF_SESSION_STORE: "memory",
    ORDERPILOT_PUBLIC_ORIGIN: PUBLIC,
    CORE_API_BASE_URL: "http://127.0.0.1:8080",
    ORDERPILOT_GATEWAY_SHARED_SECRET: "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517",
    ORDERPILOT_OIDC_ENABLED: "true",
    ORDERPILOT_OIDC_ISSUER: ISSUER,
    ORDERPILOT_OIDC_CLIENT_ID: "operant-dashboard-client",
    ORDERPILOT_OIDC_CLIENT_SECRET: "readiness-secret-0123456789abcdef",
    ORDERPILOT_OIDC_REDIRECT_URI: `${PUBLIC}/api/auth/oidc/callback`,
    ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${PUBLIC}/login`,
    ORDERPILOT_OIDC_SCOPES: "openid profile email",
    ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "CLIENT_SECRET_BASIC",
    [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: JSON.stringify({ mappings: [{ issuer: ISSUER, subject: "sub", audience: "operant-dashboard-client", enabled: true, accessPlane: "TENANT_USER", tenantRef: "11111111-1111-4111-8111-111111111111", actorRef: "22222222-2222-4222-8222-222222222222", bffPermissions: ["REVIEW_READ"], mappingVersion: "v1" }] })
  };
  function metadataFetch(metadata = {}) {
    return async () => new Response(JSON.stringify({
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/authorize`,
      token_endpoint: `${ISSUER}/token`,
      jwks_uri: `${ISSUER}/jwks`,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["client_secret_basic"],
      scopes_supported: ["openid", "profile", "email"],
      id_token_signing_alg_values_supported: ["RS256"],
      ...metadata
    }), { headers: { "Content-Type": "application/json" } });
  }
  async function withEnv(vars, fn) {
    const prior = {};
    for (const key of ENV_KEYS) { prior[key] = process.env[key]; delete process.env[key]; }
    Object.assign(process.env, vars);
    try { return await fn(); } finally {
      for (const key of ENV_KEYS) { if (prior[key] === undefined) delete process.env[key]; else process.env[key] = prior[key]; }
    }
  }
  test("OIDC readiness fails closed for missing config mapping provider and passes valid complete config", async () => {
    await withEnv({}, async () => assert.deepEqual(await validateOidcProductionReadiness({ fetch: metadataFetch(), cache: createOidcRuntimeCache() }), { ok: false, code: "BFF_CONFIG_INVALID" }));
    await withEnv({ ...BASE, ORDERPILOT_OIDC_CLIENT_SECRET: "" }, async () => assert.deepEqual(await validateOidcProductionReadiness({ fetch: metadataFetch(), cache: createOidcRuntimeCache() }), { ok: false, code: "OIDC_CONFIG_INVALID" }));
    await withEnv({ ...BASE, [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: "[]" }, async () => assert.deepEqual(await validateOidcProductionReadiness({ fetch: metadataFetch(), cache: createOidcRuntimeCache() }), { ok: false, code: "OIDC_MAPPING_INVALID" }));
    await withEnv(BASE, async () => assert.deepEqual(await validateOidcProductionReadiness({ fetch: metadataFetch({ issuer: "https://evil.example.test" }), cache: createOidcRuntimeCache() }), { ok: false, code: "OIDC_PROVIDER_INVALID" }));
    await withEnv(BASE, async () => assert.deepEqual(await validateOidcProductionReadiness({ fetch: metadataFetch(), cache: createOidcRuntimeCache() }), { ok: true }));
  });
}
