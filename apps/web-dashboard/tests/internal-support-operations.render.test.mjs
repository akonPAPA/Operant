// OP-CAP-56/57 — Internal Support: RUNTIME RENDER PROOF.
//
// Compiles the REAL components with the installed TypeScript compiler, stubs ONLY the network read client
// (and next/link), renders each operator-facing state with react-dom/server, and asserts on the produced
// HTML. Proves the required states (tenant locator / support-context boundary / operations summary /
// timeline / data-repair operations-view, plus empty and denied/no-grant) AND the safety boundary at render
// time:
//   - banned raw/leak sentinels placed on the fixtures NEVER reach the HTML (rawPayload, payload, secret,
//     token, credential, actorId, auditInternal, storageKey, sourceId);
//   - the read-only surfaces render no form/input/textarea/select/button mutation control;
//   - denied/no-grant/error states render only a safe mapped message (no stack trace / raw backend body).

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";

const root = process.cwd();

function transpile(source, fileName) {
  return ts.transpileModule(source, {
    compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022
    },
    fileName
  }).outputText;
}

const tmpDir = mkdtempSync(join(root, ".op57-render-"));
test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

const overviewSrc = readFileSync(join(root, "components", "support-operations-overview.tsx"), "utf8");
const detailSrc = readFileSync(join(root, "components", "data-repair-operations-view.tsx"), "utf8");
const locatorSrc = readFileSync(join(root, "components", "support-tenant-locator.tsx"), "utf8");

const apiStub = `
let f = { summary:{}, timeline:{}, view:{}, context:{}, search:{} };
export function __set(part, value) { f[part] = value; }
export async function getSupportOperationsSummary(_t) { return f.summary; }
export async function getSupportOperationsTimeline(_t, _p) { return f.timeline; }
export async function getDataRepairOperationsView(_t, _r) { return f.view; }
export async function getSupportTenantContext(_t) { return f.context; }
export async function searchSupportTenants(_q, _p) { return f.search; }
`;

const linkStub = `
import { jsx } from "react/jsx-runtime";
export default function Link({ href, children, ...rest }) {
  return jsx("a", { href, ...rest, children });
}
`;

function rewrite(js) {
  return js
    .replace(/["']@\/lib\/internal-support-operations-api["']/g, '"./api-stub.mjs"')
    .replace(/["']next\/link["']/g, '"./link-stub.mjs"');
}

writeFileSync(join(tmpDir, "api-stub.mjs"), apiStub);
writeFileSync(join(tmpDir, "link-stub.mjs"), linkStub);
writeFileSync(join(tmpDir, "support-operations-overview.mjs"), rewrite(transpile(overviewSrc, "overview.tsx")));
writeFileSync(join(tmpDir, "data-repair-operations-view.mjs"), rewrite(transpile(detailSrc, "detail.tsx")));
writeFileSync(join(tmpDir, "support-tenant-locator.mjs"), rewrite(transpile(locatorSrc, "locator.tsx")));

const overview = await import(pathToFileURL(join(tmpDir, "support-operations-overview.mjs")).href);
const detail = await import(pathToFileURL(join(tmpDir, "data-repair-operations-view.mjs")).href);
const locator = await import(pathToFileURL(join(tmpDir, "support-tenant-locator.mjs")).href);
const stub = await import(pathToFileURL(join(tmpDir, "api-stub.mjs")).href);

const TENANT = "11111111-2222-3333-4444-555555555555";

const LEAK_DECOYS = {
  rawPayload: "SENTINEL_RAW_PAYLOAD",
  payload: "SENTINEL_PAYLOAD",
  secret: "SENTINEL_SECRET",
  token: "SENTINEL_TOKEN",
  credential: "SENTINEL_CREDENTIAL",
  actorId: "SENTINEL_ACTOR_ID",
  auditInternal: "SENTINEL_AUDIT_INTERNAL",
  storageKey: "SENTINEL_STORAGE_KEY",
  sourceId: "SENTINEL_SOURCE_ID"
};
const SENTINELS = Object.values(LEAK_DECOYS);

function assertNoLeak(html) {
  for (const s of SENTINELS) {
    assert.ok(!html.includes(s), `leaked raw/internal sentinel into rendered HTML: ${s}`);
  }
  for (const key of Object.keys(LEAK_DECOYS)) {
    assert.ok(!html.includes(key), `rendered HTML must not contain raw leak key: ${key}`);
  }
}

function assertReadOnly(html) {
  for (const ctrl of ["<form", "<input", "<textarea", "<select", "<button"]) {
    assert.ok(!html.includes(ctrl), `read-only surface must not render ${ctrl}`);
  }
}

function entry(overrides) {
  return {
    category: "INCIDENT",
    eventType: "INCIDENT_CREATED",
    referenceId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    status: "OPEN",
    occurredAt: "2026-06-26T11:00:00Z",
    ...LEAK_DECOYS,
    ...overrides
  };
}

// --- OP-CAP-57 tenant locator ---

test("locator renders only tenants the actor may support; safe display, no leak, read-only", async () => {
  stub.__set("search", { data: {
    query: "acme",
    page: 0,
    pageSize: 20,
    returnedCount: 1,
    hasMore: false,
    results: [{
      tenantId: TENANT,
      displayName: "Acme Distribution",
      slug: "acme",
      status: "ACTIVE",
      supportScopes: ["DIAGNOSTICS"],
      grantExpiresAt: "2026-06-26T18:00:00Z",
      readOnly: true,
      externalExecution: "DISABLED",
      ...LEAK_DECOYS
    }],
    generatedAt: "2026-06-26T12:00:01Z"
  } });
  const html = renderToStaticMarkup(await locator.SupportTenantLocator({ query: "acme", page: 0, size: 20 }));
  assert.ok(html.includes("Acme Distribution"));
  assert.ok(html.includes("acme"));
  assert.ok(html.includes("DIAGNOSTICS"));
  // The selected tenant id appears ONLY as the navigation handle in the operations link.
  assert.ok(html.includes(`/internal-support/operations?tenantId=${TENANT}`));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("locator empty renders a safe no-access message", async () => {
  stub.__set("search", { data: {
    query: "", page: 0, pageSize: 20, returnedCount: 0, hasMore: false, results: [],
    generatedAt: "2026-06-26T12:00:01Z"
  } });
  const html = renderToStaticMarkup(await locator.SupportTenantLocator({ query: "", page: 0, size: 20 }));
  assert.ok(html.includes("No tenants match your access"));
  assertReadOnly(html);
});

test("locator error renders a safe mapped message only", async () => {
  stub.__set("search", { error: "Could not load internal support data right now. Please retry shortly." });
  const html = renderToStaticMarkup(await locator.SupportTenantLocator({ query: "x", page: 0, size: 20 }));
  assert.ok(html.includes("Could not load internal support data"));
  assert.ok(!html.toLowerCase().includes("stack"));
});

// --- OP-CAP-57 support-context (JIT grant boundary) ---

test("support-context renders the safe grant boundary; no leak; read-only", async () => {
  stub.__set("context", { data: {
    tenantId: TENANT,
    displayName: "Acme Distribution",
    slug: "acme",
    status: "ACTIVE",
    supportScopes: ["DIAGNOSTICS"],
    grantExpiresAt: "2026-06-26T18:00:00Z",
    readOnly: true,
    canViewOperations: true,
    externalExecution: "DISABLED",
    generatedAt: "2026-06-26T12:00:01Z",
    ...LEAK_DECOYS
  } });
  const html = renderToStaticMarkup(await overview.SupportTenantContextPanel({ tenantId: TENANT }));
  assert.ok(html.includes("Acme Distribution"));
  assert.ok(html.includes("DIAGNOSTICS"));
  assert.ok(html.includes("DISABLED"));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("support-context denied/no-grant renders a safe message and does not reveal tenant existence", async () => {
  stub.__set("context", { error: "You do not have an active support grant for this tenant (staff support permission and an approved, unexpired grant are required)." });
  const html = renderToStaticMarkup(await overview.SupportTenantContextPanel({ tenantId: TENANT }));
  assert.ok(html.includes("You do not have an active support grant for this tenant"));
  assert.ok(!html.toLowerCase().includes("stack"));
  assert.ok(!html.includes(TENANT));
  assertReadOnly(html);
});

// --- OP-CAP-55 operations (now tenant-scoped by the selected handle) ---

test("summary renders safe backend-derived counts for the selected tenant; no leak; read-only", async () => {
  stub.__set("summary", { data: {
    tenantId: TENANT,
    openIncidents: 2, criticalOpenIncidents: 1, pendingBreakGlassRequests: 3,
    approvedActiveBreakGlassRequests: 1, pendingSupportGrants: 0, activeSupportGrants: 4,
    pendingDataRepairApprovals: 2, approvedDataRepairRequests: 5, executedProcessingJobRepairs: 1,
    rejectedDataRepairRequests: 1, latestActivityAt: "2026-06-26T12:00:00Z",
    generatedAt: "2026-06-26T12:00:01Z", externalExecution: "DISABLED", ...LEAK_DECOYS
  } });
  const html = renderToStaticMarkup(await overview.SupportOperationsSummaryPanel({ tenantId: TENANT }));
  assert.ok(html.includes("Open incidents"));
  assert.ok(html.includes("Executed processing-job repairs"));
  assert.ok(html.includes("DISABLED"));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("timeline renders bounded events for the selected tenant; no leak; read-only", async () => {
  stub.__set("timeline", { data: {
    tenantId: TENANT, page: 0, pageSize: 20, returnedCount: 2, hasMore: true,
    entries: [entry({}), entry({ category: "DATA_REPAIR", eventType: "DATA_REPAIR_APPROVED", status: "APPROVED" })],
    generatedAt: "2026-06-26T12:00:01Z"
  } });
  const html = renderToStaticMarkup(await overview.SupportOperationsTimelinePanel({ tenantId: TENANT, page: 0, size: 20 }));
  assert.ok(html.includes("INCIDENT_CREATED"));
  assert.ok(html.includes("DATA_REPAIR_APPROVED"));
  assert.ok(html.includes("2 events"));
  // Pager links carry the selected tenant handle + page/size only.
  assert.ok(html.includes(`/internal-support/operations?tenantId=${TENANT}`));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("operations-view renders safe request diagnostics for the selected tenant; no leak; read-only", async () => {
  stub.__set("view", { data: {
    requestId: "99999999-8888-7777-6666-555555555555",
    tenantId: TENANT, targetType: "PROCESSING_JOB_STATUS_REPAIR", approvalStatus: "APPROVED",
    executionStatus: "EXECUTED", dryRunSummary: "DRY_RUN_COMPLETED", affectedTargetSummary: "1 stuck processing job",
    processingJobId: "12121212-3434-5656-7878-909090909090", previousStatus: "PROCESSING", newStatus: "FAILED",
    executedAt: "2026-06-26T12:00:00Z", executed: true,
    timeline: [entry({ category: "PROCESSING_JOB_REPAIR", eventType: "PROCESSING_JOB_REPAIR_EXECUTED", status: "EXECUTED" })],
    generatedAt: "2026-06-26T12:00:01Z", externalExecution: "DISABLED", ...LEAK_DECOYS
  } });
  const html = renderToStaticMarkup(await detail.DataRepairOperationsView({ tenantId: TENANT, requestId: "99999999-8888-7777-6666-555555555555" }));
  assert.ok(html.includes("PROCESSING_JOB_STATUS_REPAIR"));
  assert.ok(html.includes("1 stuck processing job"));
  assert.ok(html.includes("DISABLED"));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("operations-view denied renders a safe mapped message only", async () => {
  stub.__set("view", { error: "This support view was not found for this tenant context." });
  const html = renderToStaticMarkup(await detail.DataRepairOperationsView({ tenantId: TENANT, requestId: "missing" }));
  assert.ok(html.includes("This support view was not found for this tenant context."));
  assert.ok(!html.toLowerCase().includes("stack"));
  assertReadOnly(html);
});
