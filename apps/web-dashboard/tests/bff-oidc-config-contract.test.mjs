import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

import {
  OIDC_RUNTIME_IMPLEMENTED,
  oidcConfigurationDiagnostic,
  oidcReadinessState,
  publicAuthenticationCapability,
  readOidcConfigurationStatus,
  validOidcConfiguration
} from "../lib/bff/bff-oidc-config.ts";

const root = process.cwd();
const VALID_SECRET = "c1a-valid-secret-value-0123456789abcdef";
const VALID_CLIENT_ID = "operant-dashboard-client";
const VALID_ISSUER = "https://idp.example.test/tenant-a";
const VALID_PUBLIC_ORIGIN = "https://dashboard.example.test";

function validEnv(overrides = {}) {
  return {
    ORDERPILOT_DEPLOY_PROFILE: "prod",
    NODE_ENV: "test",
    ORDERPILOT_PUBLIC_ORIGIN: VALID_PUBLIC_ORIGIN,
    ORDERPILOT_OIDC_ENABLED: "true",
    ORDERPILOT_OIDC_ISSUER: VALID_ISSUER,
    ORDERPILOT_OIDC_CLIENT_ID: VALID_CLIENT_ID,
    ORDERPILOT_OIDC_CLIENT_SECRET: VALID_SECRET,
    ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback`,
    ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/login`,
    ORDERPILOT_OIDC_SCOPES: "openid profile email",
    ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "CLIENT_SECRET_BASIC",
    ...overrides
  };
}

function expectInvalid(overrides, reasonCode) {
  const status = readOidcConfigurationStatus(validEnv(overrides));
  assert.equal(status.state, "INVALID_CONFIGURATION");
  assert.equal(status.reasonCode, reasonCode);
  assert.equal(status.runtimeImplemented, false);
}

test("OIDC contract is disabled when unset or explicitly false", () => {
  assert.deepEqual(readOidcConfigurationStatus({}), {
    state: "DISABLED",
    enabled: false,
    runtimeImplemented: false
  });
  assert.deepEqual(readOidcConfigurationStatus({ ORDERPILOT_OIDC_ENABLED: "false" }), {
    state: "DISABLED",
    enabled: false,
    runtimeImplemented: false
  });
});

test("OIDC enabled flag fails closed on typo values", () => {
  expectInvalid({ ORDERPILOT_OIDC_ENABLED: "yes" }, "ENABLED_FLAG_INVALID");
});

test("valid OIDC configuration is not production-ready until runtime is implemented", () => {
  const status = readOidcConfigurationStatus(validEnv({ ORDERPILOT_OIDC_SCOPES: "openid profile email profile" }));
  assert.equal(status.state, "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED");
  assert.equal(status.runtimeImplemented, false);
  assert.equal(OIDC_RUNTIME_IMPLEMENTED, false);
  assert.notEqual(status.state, "READY");
  assert.equal(oidcReadinessState(validEnv()), "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED");
  assert.deepEqual(status.configuration.scopes, ["openid", "profile", "email"]);
  assert.equal(status.configuration.clientAuthenticationMethod, "CLIENT_SECRET_BASIC");
  assert.equal(status.configuration.issuer, VALID_ISSUER);
});

test("OIDC required provider fields and placeholders fail closed", () => {
  expectInvalid({ ORDERPILOT_PUBLIC_ORIGIN: undefined }, "PUBLIC_ORIGIN_REQUIRED");
  expectInvalid({ ORDERPILOT_PUBLIC_ORIGIN: "http://dashboard.example.test" }, "PUBLIC_ORIGIN_HTTPS_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: undefined }, "ISSUER_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "not a url" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://user:pass@idp.example.test" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://idp.example.test/tenant?x=1" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://idp.example.test/tenant#frag" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://idp.example.test:0/tenant" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "http://idp.example.test" }, "ISSUER_HTTPS_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://localhost" }, "ISSUER_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://127.2.3.4" }, "ISSUER_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://[::1]" }, "ISSUER_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://[::]" }, "ISSUER_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_ISSUER: "https://idp.example.test/${tenant}" }, "ISSUER_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_ID: undefined }, "CLIENT_ID_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_ID: "client-id" }, "CLIENT_ID_PLACEHOLDER");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_ID: "bad\u0007client" }, "CLIENT_ID_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_SECRET: undefined }, "CLIENT_SECRET_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_SECRET: "client-secret" }, "CLIENT_SECRET_PLACEHOLDER");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_SECRET: "bad\u0007secret" }, "CLIENT_SECRET_INVALID");
});

test("OIDC redirect and logout URLs are bounded to the public BFF origin", () => {
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: undefined }, "REDIRECT_URI_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: "not a url" }, "REDIRECT_URI_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: "http://dashboard.example.test/api/auth/oidc/callback" }, "REDIRECT_URI_HTTPS_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: "https://localhost/api/auth/oidc/callback" }, "REDIRECT_URI_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: "https://evil.example.test/api/auth/oidc/callback" }, "REDIRECT_URI_ORIGIN_MISMATCH");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/callback` }, "REDIRECT_URI_PATH_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback?code=x` }, "REDIRECT_URI_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback#frag` }, "REDIRECT_URI_INVALID");
  expectInvalid({ ORDERPILOT_PUBLIC_ORIGIN: "https://dashboard.example.test:8443", ORDERPILOT_OIDC_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback` }, "REDIRECT_URI_ORIGIN_MISMATCH");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: "http://dashboard.example.test/login" }, "POST_LOGOUT_REDIRECT_URI_HTTPS_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: "https://localhost/login" }, "POST_LOGOUT_REDIRECT_URI_PRODUCTION_HOST_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: "https://evil.example.test/login" }, "POST_LOGOUT_REDIRECT_URI_ORIGIN_MISMATCH");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/logout` }, "POST_LOGOUT_REDIRECT_URI_PATH_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/login?next=/admin` }, "POST_LOGOUT_REDIRECT_URI_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI: `${VALID_PUBLIC_ORIGIN}/login#signed-out` }, "POST_LOGOUT_REDIRECT_URI_INVALID");
});

test("OIDC scopes and client authentication method are explicitly bounded", () => {
  expectInvalid({ ORDERPILOT_OIDC_SCOPES: "profile email" }, "SCOPE_OPENID_REQUIRED");
  expectInvalid({ ORDERPILOT_OIDC_SCOPES: "openid offline_access" }, "SCOPE_UNSUPPORTED");
  expectInvalid({ ORDERPILOT_OIDC_SCOPES: "openid unknown" }, "SCOPE_UNSUPPORTED");
  expectInvalid({ ORDERPILOT_OIDC_SCOPES: `openid ${"x".repeat(65)}` }, "SCOPE_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_SCOPES: "openid profile email openid profile email openid profile email" }, "SCOPE_INVALID");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "client_secret_post" }, "CLIENT_AUTHENTICATION_METHOD_UNSUPPORTED");
  expectInvalid({ ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD: "client_secret_basic" }, "CLIENT_AUTHENTICATION_METHOD_UNSUPPORTED");
});

test("OIDC diagnostics and public capability expose only bounded readiness state", () => {
  const status = readOidcConfigurationStatus(validEnv());
  assert.throws(() => JSON.stringify(status), /OIDC_CONFIGURATION_STATUS_NOT_PUBLIC/);
  assert.throws(() => JSON.stringify(validOidcConfiguration(status)), /OIDC_CONFIGURATION_NOT_PUBLIC/);
  const diagnostic = JSON.stringify(oidcConfigurationDiagnostic(status));
  const publicCapability = JSON.stringify(publicAuthenticationCapability(status));
  for (const sensitive of [VALID_SECRET, VALID_CLIENT_ID, VALID_ISSUER, `${VALID_PUBLIC_ORIGIN}/api/auth/oidc/callback`]) {
    const escaped = sensitive.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    assert.doesNotMatch(diagnostic, new RegExp(escaped));
    assert.doesNotMatch(publicCapability, new RegExp(escaped));
  }
  assert.match(diagnostic, /VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED/);
  assert.match(publicCapability, /VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED/);
});

test("OIDC config stays out of public, browser, and Edge trust boundaries", () => {
  const oidcSource = readFileSync(join(root, "lib/bff/bff-oidc-config.ts"), "utf8");
  assert.doesNotMatch(oidcSource, /NEXT_PUBLIC_/);
  assert.doesNotMatch(oidcSource, /next\/headers|Request\b|\bheaders\b|\bcookies\b/);

  for (const file of ["lib/bff/bff-oidc-runtime.ts", "lib/bff/bff-oidc-runtime-network.ts"]) {
    const source = readFileSync(join(root, file), "utf8");
    assert.match(source, /import "server-only"/);
    assert.doesNotMatch(source, /NEXT_PUBLIC_|next\/headers|\bcookies\b/);
  }

  const publicConfig = readFileSync(join(root, "lib/bff/bff-public-config.ts"), "utf8");
  assert.doesNotMatch(publicConfig, /OIDC|CLIENT_SECRET|ISSUER|REDIRECT_URI/);

  const edgeEntry = readFileSync(join(root, "proxy.ts"), "utf8");
  assert.doesNotMatch(edgeEntry, /bff-oidc-config|ORDERPILOT_OIDC|OIDC/);

  for (const dir of ["app", "components"]) {
    for (const file of walk(join(root, dir))) {
      assert.doesNotMatch(readFileSync(file, "utf8"), /bff-oidc-config|bff-oidc-identity-mapping|bff-oidc-runtime|openid-client|ORDERPILOT_OIDC_CLIENT_SECRET/);
    }
  }
});

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === "node_modules" || entry === ".next") continue;
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) walk(full, out);
    else if (/\.(ts|tsx|mjs)$/.test(entry)) out.push(full);
  }
  return out;
}
