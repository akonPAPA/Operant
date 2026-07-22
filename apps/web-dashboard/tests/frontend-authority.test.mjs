import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

import {
  FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE,
  missingFrontendAuthorityMessage,
  resolveFrontendAuthority
} from "../lib/frontend-authority.mjs";

const root = process.cwd();
const authoritySource = readFileSync(join(root, "lib", "frontend-authority.mjs"), "utf8");

function frontendRuntimeSources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return frontendRuntimeSources(path);
    return /\.(?:ts|tsx|mjs)$/.test(entry.name) ? [[path, readFileSync(path, "utf8")]] : [];
  });
}

test("production resolves bff-session without private BFF env or browser tenant", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production"
  });
  assert.equal(authority.available, true);
  assert.equal(authority.mode, "bff-session");
  assert.equal("tenantId" in authority && authority.tenantId, "");
});

test("production rejects demo tenant authority even when public demo config is present", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production",
    demoMode: "true",
    demoTenantId: "browser-controlled-tenant"
  });

  assert.deepEqual(authority, {
    available: false,
    mode: "unavailable",
    reason: "DEMO_MODE_FORBIDDEN_IN_PRODUCTION"
  });
  assert.equal("tenantId" in authority, false);
});

test("production ignores demo tenant variable without demo opt-in", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production",
    demoMode: undefined,
    demoTenantId: "11111111-1111-4111-8111-111111111111"
  });

  assert.equal(authority.available, true);
  assert.equal(authority.mode, "bff-session");
  assert.equal(authority.tenantId, "");
});

test("development demo mode requires exact explicit opt-in when BFF is off", () => {
  for (const demoMode of [undefined, "", "false", "TRUE", "1"]) {
    const authority = resolveFrontendAuthority({
      nodeEnv: "development",
      demoMode,
      demoTenantId: "demo-tenant",
      bffEnabled: false
    });
    assert.equal(authority.available, false);
  }
});

test("development and test with explicit BFF flag resolve bff-session without demo tenant", () => {
  for (const nodeEnv of ["development", "test"]) {
    const authority = resolveFrontendAuthority({
      nodeEnv,
      demoMode: "false",
      bffEnabled: true
    });
    assert.equal(authority.available, true);
    assert.equal(authority.mode, "bff-session");
    assert.equal(authority.tenantId, "");
  }
});

test("explicit development and test demo mode resolve the configured tenant", () => {
  for (const nodeEnv of ["development", "test"]) {
    assert.deepEqual(
      resolveFrontendAuthority({
        nodeEnv,
        demoMode: "true",
        demoTenantId: "  demo-tenant  "
      }),
      {
        available: true,
        mode: "demo",
        tenantId: "demo-tenant"
      }
    );
  }
});

test("demo mode has no hardcoded tenant fallback", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "development",
    demoMode: "true",
    demoTenantId: ""
  });

  assert.equal(authority.available, false);
  assert.doesNotMatch(authoritySource, /11111111-1111-4111-8111-111111111111/);
});

test("resolver accepts no actor or permission authority input", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production",
    demoMode: "true",
    demoTenantId: "forged",
    actorId: "forged-actor",
    permissions: "STAFF_SUPPORT_READ"
  });

  assert.equal(authority.available, false);
  assert.equal("actorId" in authority, false);
  assert.equal("permissions" in authority, false);
  assert.doesNotMatch(authoritySource, /NEXT_PUBLIC_.*(?:ACTOR|PERMISSION)/);
  assert.doesNotMatch(authoritySource, /process\.env\.ORDERPILOT_BFF_ENABLED/);
});

test("unavailable errors are safe and do not echo config values", () => {
  const message = missingFrontendAuthorityMessage("load tenant data");
  assert.match(message, new RegExp(FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE));
  assert.doesNotMatch(message, /NEXT_PUBLIC|demo-tenant|browser-controlled/);
});

test("only the authority resolver reads browser-visible demo tenant config", () => {
  const sources = [
    ...frontendRuntimeSources(join(root, "lib")),
    ...frontendRuntimeSources(join(root, "components")),
    ...frontendRuntimeSources(join(root, "app"))
  ];

  for (const [path, source] of sources) {
    if (path.endsWith("frontend-authority.mjs")) continue;
    assert.doesNotMatch(source, /process\.env\.NEXT_PUBLIC_DEMO_TENANT_ID/, path);
    assert.doesNotMatch(source, /11111111-1111-4111-8111-111111111111/, path);
    assert.doesNotMatch(source, /NEXT_PUBLIC_[A-Z0-9_]*(?:ACTOR|PERMISSION)/, path);
  }
});

test("quote transaction client resolves demo tenant internally and accepts business intent only", () => {
  const source = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");
  assert.match(source, /requireDemoTenantId\(\)/);
  assert.doesNotMatch(source, /tenantId:\s*string/);
  assert.doesNotMatch(source, /X-OrderPilot-Actor-Id/);
  assert.doesNotMatch(source, /X-OrderPilot-Permissions/);
});

test("BFF-aware clients do not use raw tenantId truthiness as production authority", () => {
  const sources = frontendRuntimeSources(join(root, "lib"));
  const bffAwareClients = sources.filter(([path, source]) =>
    !path.includes(`${join("lib", "bff")}`) &&
    source.includes("dashboardCoreApiBaseUrl") &&
    source.includes("demoTenantId")
  );

  assert.ok(bffAwareClients.length > 0);
  for (const [path, source] of bffAwareClients) {
    assert.match(source, /isDashboardApiAuthorityAvailable\(/, `${path} must use the BFF-aware authority guard`);
    assert.doesNotMatch(
      source,
      /if\s*\(\s*![A-Za-z0-9_.]+tenantId\s*\)/,
      `${path} must not block production BFF session requests on an empty browser tenantId`
    );
  }
});
