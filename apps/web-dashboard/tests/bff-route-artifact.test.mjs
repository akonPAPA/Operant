import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { registeredBffRoutes } from "../lib/bff/bff-route-registry.ts";

/**
 * F16 — the committed BFF tenant-route artifact must exactly reflect the live registry. The Core-side
 * parity test (BffCoreRoutePolicyParityTest) reads this same artifact and runs the real
 * ApiRouteSecurityPolicy.classify() against every route, so drift here (a new/changed BFF route that
 * was not regenerated) would silently break parity. Run with UPDATE_BFF_ROUTE_ARTIFACT=1 to refresh.
 */
const ARTIFACT_URL = new URL(
  "../../../shared/contracts/bff-tenant-routes.generated.json",
  import.meta.url
);

function canonicalRoutes() {
  return registeredBffRoutes()
    .map((r) => ({
      method: r.method,
      pattern: r.pattern,
      plane: r.plane,
      permission: r.permission,
      kind: r.kind,
      csrfRequired: r.csrfRequired,
      idempotency: r.idempotency
    }))
    .sort((a, b) => (a.pattern + a.method).localeCompare(b.pattern + b.method));
}

test("F16: committed BFF route artifact matches the live registry (no drift)", () => {
  const routes = canonicalRoutes();
  if (process.env.UPDATE_BFF_ROUTE_ARTIFACT === "1") {
    const doc = {
      $comment:
        "GENERATED from bff-route-registry.ts (registeredBffRoutes). Single source: the TS registry. Regenerate via tests/bff-route-artifact.test.mjs drift guard. Core parity: BffCoreRoutePolicyParityTest.java runs the real ApiRouteSecurityPolicy.classify() against every route here.",
      version: 1,
      routes
    };
    writeFileSync(fileURLToPath(ARTIFACT_URL), JSON.stringify(doc, null, 2) + "\n");
  }
  const committed = JSON.parse(readFileSync(fileURLToPath(ARTIFACT_URL), "utf8"));
  assert.deepEqual(committed.routes, routes, "regenerate with UPDATE_BFF_ROUTE_ARTIFACT=1");
});

test("F16: every BFF route is tenant-operator plane, mutations require CSRF, reads forbid idempotency", () => {
  for (const r of canonicalRoutes()) {
    assert.equal(r.plane, "tenant-operator", `${r.method} ${r.pattern} must be tenant-operator plane`);
    if (r.kind === "mutation") {
      assert.equal(r.csrfRequired, true, `mutation ${r.pattern} must require CSRF`);
      assert.notEqual(r.idempotency, "forbidden");
    } else {
      assert.equal(r.csrfRequired, false);
      assert.equal(r.idempotency, "forbidden");
    }
  }
});
