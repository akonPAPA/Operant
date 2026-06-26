// OP-CAP-56 — Internal Support Operations Visibility: source-contract proof.
//
// Static guarantees that complement the runtime render proof:
//  - the client targets exactly the three OP-CAP-55 read-only endpoints, tenant-scoped via X-Tenant-Id;
//  - the client is GET-only and builds NO request body (so it cannot smuggle tenant/actor/staff/approval/
//    execution/audit authority into a payload — requirement #5);
//  - the only client-supplied query params are the safe bounded page/size pagination;
//  - no component/route/client references a banned raw/leak field name;
//  - the routes and navigation entry exist.

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "internal-support-operations-api.ts"), "utf8");
const overview = readFileSync(join(root, "components", "support-operations-overview.tsx"), "utf8");
const detail = readFileSync(join(root, "components", "data-repair-operations-view.tsx"), "utf8");
const overviewPage = readFileSync(join(root, "app", "(dashboard)", "internal-support", "page.tsx"), "utf8");
const detailPage = readFileSync(
  join(root, "app", "(dashboard)", "internal-support", "data-repair", "page.tsx"),
  "utf8"
);
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

// Banned raw/leak FIELD references. We match leaky identifiers (camelCase field names that never occur in
// normal prose) anywhere, plus property-access / object-key forms of the common words. This proves the
// shipped code never reads/binds a banned backend-internal field, without false-positiving on safety prose
// (e.g. "no raw payload, secret, or token is exposed"). The type definitions use only safe field names.
const BANNED_LEAK =
  /\b(rawPayload|auditInternal|storageKey|sourceId|actorId)\b|\.(payload|secret|token|credential)\b|["'](payload|secret|token|credential)["']\s*:/;
// Forbidden mutation/execution control identifiers — this surface is strictly read-only.
const FORBIDDEN_CONTROLS =
  /executeProcessingJobRepair|approveDataRepair|rejectDataRepair|requestApproval|attemptExecution|requestBreakGlass|approveBreakGlass|createGrant|revokeGrant|method:\s*"(POST|PUT|PATCH|DELETE)"/;

test("internal support routes and components exist", () => {
  assert.equal(existsSync(join(root, "lib", "internal-support-operations-api.ts")), true);
  assert.equal(existsSync(join(root, "components", "support-operations-overview.tsx")), true);
  assert.equal(existsSync(join(root, "components", "data-repair-operations-view.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "internal-support", "page.tsx")), true);
  assert.equal(
    existsSync(join(root, "app", "(dashboard)", "internal-support", "data-repair", "page.tsx")),
    true
  );
});

test("client targets the three OP-CAP-55 read-only endpoints, tenant-scoped", () => {
  assert.match(apiClient, /\/api\/v1\/internal\/support\/tenants\/\$\{internalSupportConfig\.tenantId\}/);
  assert.match(apiClient, /\/operations\/summary/);
  assert.match(apiClient, /\/operations\/timeline/);
  assert.match(apiClient, /\/data-repair-requests\/\$\{encodeURIComponent\(requestId\)\}\/operations-view/);
  assert.match(apiClient, /X-Tenant-Id/);
  // Tenant scope comes from server-resolved demo scope, not operator input.
  assert.match(apiClient, /NEXT_PUBLIC_DEMO_TENANT_ID/);
});

test("client is GET-only and builds no request body (request contract: no authority payload)", () => {
  assert.match(apiClient, /method: "GET"/);
  // No non-GET method and no request body anywhere in the client.
  assert.doesNotMatch(apiClient, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
  assert.doesNotMatch(apiClient, /\bbody:/);
});

test("client sends only the safe bounded page/size pagination params", () => {
  const setCalls = [...apiClient.matchAll(/search\.set\(\s*"([^"]+)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(setCalls, ["page", "size"]);
});

test("client and views reference no banned raw/leak field names", () => {
  assert.doesNotMatch(apiClient, BANNED_LEAK);
  assert.doesNotMatch(overview, BANNED_LEAK);
  assert.doesNotMatch(detail, BANNED_LEAK);
  assert.doesNotMatch(overviewPage, BANNED_LEAK);
  assert.doesNotMatch(detailPage, BANNED_LEAK);
});

test("read-only surface references no mutation/execution control", () => {
  assert.doesNotMatch(apiClient, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(overview, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(detail, FORBIDDEN_CONTROLS);
});

test("forbidden/error states map to safe operator messages, not raw backend bodies", () => {
  assert.match(apiClient, /You do not have access to this internal support view/);
  assert.match(apiClient, /Could not load internal support operations/);
  // Raw response text is drained but never returned to the UI on a non-200.
  assert.match(apiClient, /Drain the body/);
});

test("navigation exposes an Internal Support entry under Control Center", () => {
  assert.match(navigation, /label: "Internal Support", href: "\/internal-support"/);
});
