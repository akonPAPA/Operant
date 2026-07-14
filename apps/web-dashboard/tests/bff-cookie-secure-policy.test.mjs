import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { isSecureCookieDeployment } from "../lib/bff/bff-deployment-profile.ts";
import { bffCookieSecure } from "../lib/bff/bff-config.ts";

const ENV_KEYS = ["NODE_ENV", "ORDERPILOT_DEPLOY_PROFILE"];

function withDeployment(vars, fn) {
  const prior = {};
  for (const key of ENV_KEYS) {
    prior[key] = process.env[key];
    delete process.env[key];
  }
  Object.assign(process.env, vars);
  try {
    return fn();
  } finally {
    for (const key of ENV_KEYS) {
      if (prior[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prior[key];
      }
    }
  }
}

// F08: one fail-safe predicate governs Secure for both issuance and clearing.
const SECURE_MATRIX = [
  // NODE_ENV=production is always Secure, regardless of deploy profile (never downgraded)
  ...["production", "prod", "cloud", "staging", "local", "test", "local-test", "", "unknown"].map((profile) => ({
    env: { NODE_ENV: "production", ORDERPILOT_DEPLOY_PROFILE: profile },
    secure: true
  })),
  // Production-like deploy profiles are Secure even when NODE_ENV is not production
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "production" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "prod" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "cloud" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "staging" }, secure: true },
  // Unknown / missing profile fails safe -> Secure
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "" }, secure: true },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "unknown-profile" }, secure: true },
  { env: { NODE_ENV: "development", ORDERPILOT_DEPLOY_PROFILE: "" }, secure: true },
  // Explicit local/test profiles (non-production runtime) may omit Secure
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "local" }, secure: false },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "test" }, secure: false },
  { env: { NODE_ENV: "test", ORDERPILOT_DEPLOY_PROFILE: "local-test" }, secure: false }
];

test("F08: Secure cookie predicate matches the required profile x NODE_ENV matrix", () => {
  for (const { env, secure } of SECURE_MATRIX) {
    withDeployment(env, () => {
      assert.equal(
        isSecureCookieDeployment(),
        secure,
        `isSecureCookieDeployment ${JSON.stringify(env)} -> ${secure}`
      );
      assert.equal(bffCookieSecure(), secure, `bffCookieSecure ${JSON.stringify(env)} -> ${secure}`);
    });
  }
});

test("F08: bffCookieSecure delegates to the single fail-safe predicate", () => {
  for (const { env } of SECURE_MATRIX) {
    withDeployment(env, () => {
      assert.equal(bffCookieSecure(), isSecureCookieDeployment());
    });
  }
});

test("F08: issuance and clearing derive attributes from the same helper", () => {
  // Structural guarantee: both the Set-Cookie issuance and the clearing use cookieAttributes(...),
  // which is the only place that reads bffCookieSecure() — so a cleared cookie always carries
  // Secure/Path/SameSite attributes compatible with how it was issued.
  const source = readFileSync(
    fileURLToPath(new URL("../lib/bff/bff-auth-handlers.ts", import.meta.url)),
    "utf8"
  );
  // cookieAttributes is defined once and is the sole Secure gate.
  assert.match(source, /function cookieAttributes\([^)]*\)[^{]*\{[^}]*bffCookieSecure\(\)/s);
  // Session issuance, CSRF issuance, session clearing, and CSRF clearing all route through it.
  const cookieAttrUses = source.match(/cookieAttributes\(/g) ?? [];
  assert.ok(cookieAttrUses.length >= 4, `expected >=4 cookieAttributes() uses, saw ${cookieAttrUses.length}`);
  // Session cookies (issued and cleared) remain HttpOnly.
  assert.match(source, new RegExp("BFF_SESSION_COOKIE\\}=\\$\\{sessionId\\}; HttpOnly;"));
  assert.match(source, new RegExp("BFF_SESSION_COOKIE\\}=; HttpOnly;"));
});
