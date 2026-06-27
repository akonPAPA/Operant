// OP-CAP-56/57 — Internal Support: source-contract proof.
//
// Static guarantees that complement the runtime render proof:
//  - the client targets exactly the OP-CAP-55/57 read-only endpoints (locator + support-context + the three
//    operations endpoints), tenant-scoped by the SELECTED tenant handle via X-Tenant-Id (locator is
//    cross-tenant and sends no tenant header);
//  - the client is GET-only and builds NO request body (so it cannot smuggle tenant/actor/staff/approval/
//    execution/audit authority into a payload — requirement #4);
//  - the only client-supplied query params are the safe bounded query/page/size;
//  - the selected tenant id is a navigation handle resolved from the locator, NOT the demo env var;
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
const locator = readFileSync(join(root, "components", "support-tenant-locator.tsx"), "utf8");
const landingPage = readFileSync(join(root, "app", "(dashboard)", "internal-support", "page.tsx"), "utf8");
const operationsPage = readFileSync(
  join(root, "app", "(dashboard)", "internal-support", "operations", "page.tsx"),
  "utf8"
);
const detailPage = readFileSync(
  join(root, "app", "(dashboard)", "internal-support", "data-repair", "page.tsx"),
  "utf8"
);
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

// Banned raw/leak FIELD references (requirement #4 banned list, plus connectorSecret/apiKey/password). We
// match leaky identifiers (camelCase field names that never occur in normal prose) anywhere, plus
// property-access / object-key forms of the common words. This proves the shipped code never reads/binds a
// banned backend-internal field without false-positiving on safety prose.
const BANNED_LEAK =
  /\b(rawPayload|auditInternal|storageKey|sourceId|actorId|connectorSecret|apiKey)\b|\.(payload|secret|token|credential|password)\b|["'](payload|secret|token|credential|password)["']\s*:/;
// Forbidden mutation/execution/authority control identifiers — this surface is strictly read-only.
const FORBIDDEN_CONTROLS =
  /executeProcessingJobRepair|approveDataRepair|rejectDataRepair|requestApproval|attemptExecution|requestBreakGlass|approveBreakGlass|createGrant|revokeGrant|method:\s*"(POST|PUT|PATCH|DELETE)"/;

test("internal support routes and components exist", () => {
  assert.equal(existsSync(join(root, "lib", "internal-support-operations-api.ts")), true);
  assert.equal(existsSync(join(root, "components", "support-tenant-locator.tsx")), true);
  assert.equal(existsSync(join(root, "components", "support-operations-overview.tsx")), true);
  assert.equal(existsSync(join(root, "components", "data-repair-operations-view.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "internal-support", "page.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "internal-support", "operations", "page.tsx")), true);
  assert.equal(
    existsSync(join(root, "app", "(dashboard)", "internal-support", "data-repair", "page.tsx")),
    true
  );
});

test("client targets the OP-CAP-57 locator + support-context + OP-CAP-55 operations endpoints", () => {
  assert.match(apiClient, /\/api\/v1\/internal\/support\/tenants\/search/);
  assert.match(apiClient, /\/support-context/);
  assert.match(apiClient, /\/operations\/summary/);
  assert.match(apiClient, /\/operations\/timeline/);
  assert.match(apiClient, /\/data-repair-requests\/\$\{encodeURIComponent\(requestId\)\}\/operations-view/);
  // Tenant-scoped reads pass the SELECTED tenant handle via X-Tenant-Id (not a demo env tenant).
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /tenantBase\(tenantId\)/);
});

test("client no longer derives the tenant from a demo env var as the access model", () => {
  // OP-CAP-57: the operations calls take a selected tenantId argument; the demo tenant env is not the
  // authority source anymore (the client must not read NEXT_PUBLIC_DEMO_TENANT_ID for tenant scope).
  assert.doesNotMatch(apiClient, /NEXT_PUBLIC_DEMO_TENANT_ID/);
  assert.match(apiClient, /getSupportOperationsSummary\(\s*\n?\s*tenantId/);
});

test("locator search is cross-tenant: it sends no X-Tenant-Id header", () => {
  // searchSupportTenants calls getJson WITHOUT a tenantId arg (cross-tenant discovery).
  assert.match(apiClient, /searchSupportTenants\(/);
  assert.match(apiClient, /getJson<SupportTenantSearch>\(`\/api\/v1\/internal\/support\/tenants\/search/);
});

test("client is GET-only and builds no request body (request contract: no authority payload)", () => {
  assert.match(apiClient, /method: "GET"/);
  assert.doesNotMatch(apiClient, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
  assert.doesNotMatch(apiClient, /\bbody:/);
});

test("client sends only the safe bounded query/page/size params", () => {
  const setCalls = [...apiClient.matchAll(/search\.set\(\s*"([^"]+)"/g)].map((m) => m[1]);
  const distinct = [...new Set(setCalls)].sort();
  assert.deepEqual(distinct, ["page", "query", "size"]);
});

test("client and views reference no banned raw/leak field names", () => {
  assert.doesNotMatch(apiClient, BANNED_LEAK);
  assert.doesNotMatch(overview, BANNED_LEAK);
  assert.doesNotMatch(detail, BANNED_LEAK);
  assert.doesNotMatch(locator, BANNED_LEAK);
  assert.doesNotMatch(landingPage, BANNED_LEAK);
  assert.doesNotMatch(operationsPage, BANNED_LEAK);
  assert.doesNotMatch(detailPage, BANNED_LEAK);
});

test("read-only surface references no mutation/execution control", () => {
  assert.doesNotMatch(apiClient, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(overview, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(detail, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(locator, FORBIDDEN_CONTROLS);
});

test("locator/operations forms carry no staff-actor or authority input fields", () => {
  // Only safe navigation/locator inputs are allowed: q (search), requestId, and the tenantId handle.
  const forbiddenInputs = /name="(actorId|staffActor|staffUserId|approvalStatus|executionStatus|scope|grantId|status|permission)"/;
  assert.doesNotMatch(landingPage, forbiddenInputs);
  assert.doesNotMatch(operationsPage, forbiddenInputs);
  assert.doesNotMatch(detailPage, forbiddenInputs);
});

test("forbidden/error states map to safe operator messages, not raw backend bodies", () => {
  assert.match(apiClient, /You do not have an active support grant for this tenant/);
  assert.match(apiClient, /Could not load internal support data/);
  assert.match(apiClient, /Drain the body/);
});

test("navigation exposes an Internal Support entry under Control Center", () => {
  assert.match(navigation, /label: "Internal Support", href: "\/internal-support"/);
});
