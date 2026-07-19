import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  assertSurfaceContractInvariants,
  capabilitiesImpliedByRoutes,
  findRegisteredBffRoute,
  frontendSurfaceContracts,
  shippedTenantSurfaceContracts,
  surfaceContractForPath
} from "../lib/frontend-surface-contract.ts";
import { registeredBffRoutes } from "../lib/bff/bff-route-registry.ts";
import {
  navigationDestinations,
  paletteDestinations,
  tenantPrimaryDestinations,
  UNIVERSAL_TENANT_PATHS
} from "../components/navigation-registry.ts";
import { PERMISSION_TO_UI_CAPABILITY, UI_CAPABILITIES } from "../lib/ui-capability-model.ts";

const ROOT = join(process.cwd());

test("every navigation destination has exactly one surface contract", () => {
  const contracts = frontendSurfaceContracts();
  assert.equal(contracts.length, navigationDestinations.length);
  const ids = new Set(contracts.map((c) => c.surfaceId));
  assert.equal(ids.size, contracts.length);
  for (const dest of navigationDestinations) {
    assert.ok(ids.has(dest.id), `missing contract for ${dest.id}`);
  }
});

test("every surface contract satisfies executable invariants", () => {
  for (const surface of frontendSurfaceContracts()) {
    assert.doesNotThrow(() => assertSurfaceContractInvariants(surface), surface.surfaceId);
  }
});

test("shipped TENANT surfaces never include wrong access planes", () => {
  for (const surface of shippedTenantSurfaceContracts()) {
    assert.equal(surface.accessPlane, "TENANT");
    assert.notEqual(surface.availability, "UNSUPPORTED");
  }
  for (const plane of ["OPERANT_STAFF", "EXTERNAL_CUSTOMER", "SERVICE", "PUBLIC", "INTERNAL_ONLY"]) {
    const leaked = tenantPrimaryDestinations().filter((d) => d.plane === plane);
    assert.equal(leaked.length, 0, `tenant primary must not include ${plane}`);
    const paletteLeak = paletteDestinations("TENANT").filter((d) => d.plane === plane);
    assert.equal(paletteLeak.length, 0);
  }
});

test("navigation destination → surface contract → BFF routes → permissions → UI capability", () => {
  for (const dest of navigationDestinations.filter((d) => d.plane === "TENANT")) {
    const surface = surfaceContractForPath(dest.path);
    assert.notEqual(surface, null, dest.path);
    assert.equal(surface?.surfaceId, dest.id);

    for (const ref of surface.consumedBffRoutes) {
      const rule = findRegisteredBffRoute(ref);
      assert.notEqual(rule, null, `${dest.id} ${ref.pattern}`);
      const capability = PERMISSION_TO_UI_CAPABILITY[rule.permission];
      if (!capability) {
        continue;
      }
      assert.ok(UI_CAPABILITIES.includes(capability));
      const ruleKind = surface.requiredCapabilityRule.kind;
      if (ruleKind === "ANY_OF" || ruleKind === "ALL_OF" || ruleKind === "DEPLOYMENT_GATED") {
        assert.ok(
          surface.requiredCapabilityRule.capabilities.includes(capability),
          `${dest.id}: route permission ${rule.permission} → ${capability} not in offer rule`
        );
      }
    }
  }
});

test("universal surfaces consume no protected BFF routes and stay in UNIVERSAL_TENANT_PATHS", () => {
  for (const path of UNIVERSAL_TENANT_PATHS) {
    const surface = surfaceContractForPath(path);
    assert.notEqual(surface, null, path);
    assert.equal(surface.requiredCapabilityRule.kind, "NONE");
    assert.equal(surface.consumedBffRoutes.length, 0);
    assert.ok(surface.universalReason);
  }
  assert.equal(UNIVERSAL_TENANT_PATHS.has("/upload"), false);
});

test("integrations is unsupported and absent from tenant offer surfaces", () => {
  const surface = surfaceContractForPath("/integrations");
  assert.equal(surface?.requiredCapabilityRule.kind, "UNSUPPORTED");
  assert.equal(surface?.availability, "UNSUPPORTED");
  assert.equal(tenantPrimaryDestinations().some((d) => d.path === "/integrations"), false);
  assert.equal(paletteDestinations("TENANT").some((d) => d.path === "/integrations"), false);
});

test("upload is deployment-gated with VIEW_DOCUMENTS and not universal", () => {
  const surface = surfaceContractForPath("/upload");
  assert.equal(surface?.requiredCapabilityRule.kind, "DEPLOYMENT_GATED");
  assert.deepEqual(surface?.requiredCapabilityRule.capabilities, ["VIEW_DOCUMENTS"]);
  assert.equal(UNIVERSAL_TENANT_PATHS.has("/upload"), false);
  const withoutDocs = tenantPrimaryDestinations(new Set());
  assert.equal(withoutDocs.some((d) => d.path === "/upload"), false);
  const withDocs = tenantPrimaryDestinations(new Set(["VIEW_DOCUMENTS"]));
  assert.equal(withDocs.some((d) => d.path === "/upload"), true);
});

test("unknown capability offers nothing; unknown route grants nothing", () => {
  const offered = tenantPrimaryDestinations(new Set(["NOT_A_REAL_CAPABILITY"]));
  assert.equal(
    offered.filter((d) => d.capability !== null).length,
    0,
    "unknown capability must not unlock gated destinations"
  );
  assert.ok(offered.every((d) => d.capability === null));
  assert.equal(findRegisteredBffRoute({ method: "GET", pattern: "api/v1/not-registered" }), null);
  assert.equal(capabilitiesImpliedByRoutes([{ method: "GET", pattern: "api/v1/not-registered" }]).size, 0);
});

test("command-center page server-gates analytics panels by VIEW_ANALYTICS", () => {
  const page = readFileSync(join(ROOT, "app/(dashboard)/command-center/page.tsx"), "utf8");
  assert.match(page, /loadUiCapabilityProjection/);
  assert.match(page, /VIEW_ANALYTICS/);
  assert.match(page, /showAnalytics/);
  assert.match(page, /OperantCommandCenter/);
  assert.match(page, /CommandCenterAnalytics/);
  assert.match(page, /BusinessValueAnalytics/);
});

test("integrations page no longer mounts IntegrationControl (denied-as-empty closed)", () => {
  const page = readFileSync(join(ROOT, "app/(dashboard)/integrations/page.tsx"), "utf8");
  assert.match(page, /UnavailableState/);
  assert.doesNotMatch(page, /IntegrationControl/);
});

test("registeredBffRoutes remains the permission source for declared surface consumption", () => {
  const registered = new Set(registeredBffRoutes().map((r) => `${r.method} ${r.pattern}`));
  for (const surface of frontendSurfaceContracts()) {
    for (const ref of [...surface.consumedBffRoutes, ...surface.mutationRoutes]) {
      assert.ok(registered.has(`${ref.method} ${ref.pattern}`), `${surface.surfaceId} ${ref.pattern}`);
    }
  }
});
