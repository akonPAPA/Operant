import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { isSecureCookieDeployment } from "../lib/bff/bff-deployment-profile.ts";
import { bffCookieSecure } from "../lib/bff/bff-config.ts";

const ENV_KEYS = ["NODE_ENV", "ORDERPILOT_DEPLOY_PROFILE"];
const SECURE_MATRIX = [
  ...["production", "prod", "cloud", "staging", "local", "test", "local-test", "", "unknown"].map((profile) => ({
    env: { NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: profile }, secure: true
  })),
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "production" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "prod" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "cloud" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "staging" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "unknown-profile" }, secure: true },
  { env: { NODE_ENV: "development", ORDERPILOT_DEPLOY_PROFILE: "" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "local" }, secure: false },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "test" }, secure: false },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "local-test" }, secure: false }
];

function withDeployment(vars, fn) {
  const prior = Object.fromEntries(ENV_KEYS.map((key) => [key, process.env[key]]));
  for (const key of ENV_KEYS) delete process.env[key];
  Object.assign(process.env, vars);
  try {
    return fn();
  } finally {
    for (const key of ENV_KEYS) {
      if (prior[key] === undefined) delete process.env[key];
      else process.env[key] = prior[key];
    }
  }
}

test("F08: Secure cookie predicate matches the profile matrix", () => {
  for (const { env, secure } of SECURE_MATRIX) {
    withDeployment(env, () => {
      assert.equal(isSecureCookieDeployment(), secure);
      assert.equal(bffCookieSecure(), secure);
    });
  }
});

test("F08: auth and OIDC binding cookies share the fail-safe Secure predicate", () => {
  const authWriter = readFileSync(
    fileURLToPath(new URL("../lib/bff/bff-auth-cookie-writer.ts", import.meta.url)),
    "utf8"
  );
  const bindingWriter = readFileSync(
    fileURLToPath(new URL("../lib/bff/bff-oidc-browser-binding.ts", import.meta.url)),
    "utf8"
  );
  for (const source of [authWriter, bindingWriter]) {
    assert.match(source, /function cookieAttributes\([^)]*\)[^{]*\{[^}]*bffCookieSecure\(\)/s);
    assert.match(source, /Path=\/; Max-Age=/);
    assert.match(source, /SameSite=Lax/);
  }
  assert.match(authWriter, /BFF_SESSION_COOKIE.*HttpOnly/s);
  assert.match(bindingWriter, /OIDC_LOGIN_BINDING_COOKIE.*HttpOnly/s);
});
