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

test("production with BFF enabled resolves bff-session without browser tenant", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production",
    bffEnabled: "true"
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

test("production does not use the demo tenant variable without demo opt-in", () => {
  const authority = resolveFrontendAuthority({
    nodeEnv: "production",
    demoMode: undefined,
    demoTenantId: "11111111-1111-4111-8111-111111111111"
  });

  assert.equal(authority.available, false);
  assert.equal("tenantId" in authority, false);
});

test("development demo mode requires exact explicit opt-in", () => {
  for (const demoMode of [undefined, "", "false", "TRUE", "1"]) {
    const authority = resolveFrontendAuthority({
      nodeEnv: "development",
      demoMode,
      demoTenantId: "demo-tenant"
    });
    assert.equal(authority.available, false);
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

test("permission headers are unreachable when the demo tenant resolver fails closed", () => {
  const sources = frontendRuntimeSources(join(root, "lib"));
  const permissionClients = sources.filter(([path, source]) =>
    source.includes('"X-OrderPilot-Permissions"') && !path.includes(`${join("lib", "bff")}`)
  );

  assert.ok(permissionClients.length > 0);
  for (const [path, source] of permissionClients) {
    assert.match(
      source,
      /if\s*\(\s*![A-Za-z0-9]+\.tenantId\s*\)/,
      `${path} must stop before fetch when production has no trusted tenant authority`
    );
  }
});
