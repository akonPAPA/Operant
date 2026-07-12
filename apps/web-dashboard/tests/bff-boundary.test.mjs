import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { isBffProxyPathAllowed, matchBffRoute } from "../lib/bff/bff-route-registry.ts";
import { createSessionToken, parseSessionToken } from "./helpers/bff-stateless-session-token.ts";
import { isLocalTestBootstrapAllowed, isProductionLikeDeployment } from "../lib/bff/bff-deployment-profile.ts";
import { validateCsrf, validateSameOrigin } from "../lib/bff/bff-csrf.ts";

const root = process.cwd();

test("bff route registry is default-deny with explicit per-route method binding", () => {
  // registered
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quote-review", "queue"], "GET"), true);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes", "11111111-1111-4111-8111-111111111111", "approve"], "POST"), true);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes", "q1", "approve"], "POST"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "order-journeys", "22222222-2222-4222-8222-222222222222"], "GET"), true);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "order-journeys", "j1"], "GET"), false);
  // wrong method on a registered path
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quote-review", "queue"], "POST"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes", "11111111-1111-4111-8111-111111111111", "approve"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes", "11111111-1111-4111-8111-111111111111", "approve"], "DELETE"), false);
  // formerly broad prefixes no longer forward unknown sub-routes
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes", "brand-new-endpoint"], "POST"), false);
  assert.equal(isBffProxyPathAllowed(["api", "stage8", "new-surface"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "stage9", "connectors", "credentials"], "GET"), false);
  // unknown operator routes stay denied
  assert.equal(isBffProxyPathAllowed(["api", "v1", "unknown-operator"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "unknown-operator"], "DELETE"), false);
  // denied planes
  assert.equal(isBffProxyPathAllowed(["api", "v1", "demo", "rfq-handoff"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "webhooks", "telegram"], "POST"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "internal", "support", "tenants", "search"], "GET"), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "public", "order-tracking", "tok"], "GET"), false);
});

test("registered mutations bind permission, content type, size and CSRF", () => {
  const rule = matchBffRoute(["api", "v1", "quote-review", "33333333-3333-4333-8333-333333333333", "assemble-draft"], "POST");
  assert.equal(rule.kind, "mutation");
  assert.equal(rule.permission, "REVIEW_ACTION");
  assert.equal(rule.contentType, "application/json");
  assert.ok(rule.maxBodyBytes > 0);
  assert.equal(rule.csrfRequired, true);
  const readRule = matchBffRoute(["api", "v1", "analytics", "overview"], "GET");
  assert.equal(readRule.kind, "read");
  assert.equal(readRule.permission, "ANALYTICS_READ");
});

test("bff session signed token helpers remain for compatibility tests", () => {
  const secret = "x".repeat(32);
  const token = createSessionToken(
    {
      tenantId: "11111111-1111-4111-8111-111111111111",
      actorId: "22222222-2222-4222-8222-222222222222",
      permissions: ["REVIEW_READ"],
      expiresAtEpochSec: Math.floor(Date.now() / 1000) + 60
    },
    secret
  );
  const parsed = parseSessionToken(token, secret);
  assert.equal(parsed?.tenantId, "11111111-1111-4111-8111-111111111111");
  assert.equal(parseSessionToken(`${token}tampered`, secret), null);
});

test("production-like deployment rejects local bootstrap flag", () => {
  const priorNode = process.env.NODE_ENV;
  const priorProfile = process.env.ORDERPILOT_DEPLOY_PROFILE;
  const priorFlag = process.env.ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP;
  const priorBff = process.env.ORDERPILOT_BFF_ENABLED;
  try {
    process.env.NODE_ENV = "production";
    delete process.env.ORDERPILOT_DEPLOY_PROFILE;
    process.env.ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP = "true";
    process.env.ORDERPILOT_BFF_ENABLED = "true";
    assert.equal(isProductionLikeDeployment(), true);
    assert.equal(isLocalTestBootstrapAllowed(), false);
    process.env.ORDERPILOT_DEPLOY_PROFILE = "staging";
    assert.equal(isLocalTestBootstrapAllowed(), false);
    process.env.ORDERPILOT_DEPLOY_PROFILE = "local-test";
    assert.equal(isProductionLikeDeployment(), true, "local-test must not downgrade production NODE_ENV");
    assert.equal(isLocalTestBootstrapAllowed(), false);
  } finally {
    if (priorNode === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = priorNode;
    if (priorProfile === undefined) {
      delete process.env.ORDERPILOT_DEPLOY_PROFILE;
    } else {
      process.env.ORDERPILOT_DEPLOY_PROFILE = priorProfile;
    }
    if (priorFlag === undefined) delete process.env.ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP;
    else process.env.ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP = priorFlag;
    if (priorBff === undefined) delete process.env.ORDERPILOT_BFF_ENABLED;
    else process.env.ORDERPILOT_BFF_ENABLED = priorBff;
  }
});

test("csrf and same-origin validation", () => {
  const token = "csrf-token-0123456789abcdef";
  const withHeader = (value) => new Request("http://x", { headers: { "X-OP-CSRF-Token": value } });
  assert.equal(validateCsrf(withHeader(token), token), true);
  assert.equal(validateCsrf(withHeader(token), "other-token-0123456789abcdef"), false);
  assert.equal(validateCsrf(withHeader("short"), "short"), false, "malformed short token rejected");
  assert.equal(validateCsrf(new Request("http://x"), token), false);

  const priorOrigin = process.env.ORDERPILOT_PUBLIC_ORIGIN;
  const priorProfile = process.env.ORDERPILOT_DEPLOY_PROFILE;
  const priorNode = process.env.NODE_ENV;
  process.env.ORDERPILOT_DEPLOY_PROFILE = "local-test";
  process.env.NODE_ENV = "test";
  process.env.ORDERPILOT_PUBLIC_ORIGIN = "http://localhost:3000";
  try {
    const originReq = new Request("http://localhost:3000/api/bff/x", {
      method: "POST",
      headers: { origin: "http://localhost:3000", host: "evil.example", "x-forwarded-host": "evil.example" }
    });
    assert.equal(validateSameOrigin(originReq), true, "Host/Forwarded must not affect public origin");
    const crossReq = new Request("http://localhost:3000/api/bff/x", {
      method: "POST",
      headers: { origin: "https://attacker.example", host: "localhost:3000" }
    });
    assert.equal(validateSameOrigin(crossReq), false);
    const refererReq = new Request("http://localhost:3000/api/bff/x", {
      method: "POST",
      headers: { referer: "http://localhost:3000/page" }
    });
    assert.equal(validateSameOrigin(refererReq), true);
    const missingBoth = new Request("http://localhost:3000/api/bff/x", { method: "POST" });
    assert.equal(validateSameOrigin(missingBoth), false);
  } finally {
    if (priorOrigin === undefined) delete process.env.ORDERPILOT_PUBLIC_ORIGIN;
    else process.env.ORDERPILOT_PUBLIC_ORIGIN = priorOrigin;
    if (priorProfile === undefined) delete process.env.ORDERPILOT_DEPLOY_PROFILE;
    else process.env.ORDERPILOT_DEPLOY_PROFILE = priorProfile;
    if (priorNode === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = priorNode;
  }
});

test("bff proxy validates session and csrf before upstream fetch", () => {
  const proxy = readFileSync(join(root, "lib", "bff", "bff-proxy.ts"), "utf8");
  const csrfIdx = proxy.indexOf("validateCsrf");
  const fetchIdx = proxy.indexOf("await fetch(target");
  assert.ok(csrfIdx > 0 && fetchIdx > csrfIdx, "CSRF must be checked before Core fetch");
  assert.match(proxy, /loadOperatorSession/);
  assert.doesNotMatch(proxy, /parseSessionToken\(/);
});

test("bff proxy returns before Core fetch when CSRF validation fails", () => {
  const proxy = readFileSync(join(root, "lib", "bff", "bff-proxy.ts"), "utf8");
  const csrfBlock = proxy.slice(proxy.indexOf("if (!validateCsrf"), proxy.indexOf("await fetch(target"));
  assert.match(csrfBlock, /safeJson\(403\)/);
  assert.doesNotMatch(csrfBlock, /await fetch\(/);
});

test("every mutation client routes through the shared CSRF-aware helper", () => {
  const libDir = join(root, "lib");
  const offenders = [];
  for (const entry of readdirSync(libDir)) {
    if (!entry.endsWith(".ts")) {
      continue;
    }
    const source = readFileSync(join(libDir, entry), "utf8");
    const hasMutation = /method:\s*"(POST|PUT|PATCH|DELETE)"/.test(source);
    if (!hasMutation) {
      continue;
    }
    const usesSharedHelper =
      /enrichDashboardRequestInit|dashboardFetch/.test(source);
    if (!usesSharedHelper) {
      offenders.push(entry);
    }
  }
  assert.deepEqual(offenders, [], "mutation clients must use enrichDashboardRequestInit/dashboardFetch");
});
