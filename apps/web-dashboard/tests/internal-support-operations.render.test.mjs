// OP-CAP-56 — Internal Support Operations Visibility: RUNTIME RENDER PROOF.
//
// Compiles the REAL OP-CAP-56 components with the installed TypeScript compiler, stubs ONLY the network
// read client (and next/link), renders each operator-facing state with react-dom/server, and asserts on
// the produced HTML. Proves the required states (summary / timeline / data-repair operations-view, plus
// empty and forbidden/error) AND the safety boundary at render time:
//   - banned raw/leak sentinels placed on the fixtures NEVER reach the HTML (rawPayload, payload, secret,
//     token, credential, actorId, auditInternal, storageKey, sourceId);
//   - the read-only surfaces render no form/input/textarea/select/button mutation control;
//   - forbidden/error states render only a safe mapped message (no stack trace / raw backend body).

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

const tmpDir = mkdtempSync(join(root, ".op56-render-"));
test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

const overviewSrc = readFileSync(join(root, "components", "support-operations-overview.tsx"), "utf8");
const detailSrc = readFileSync(join(root, "components", "data-repair-operations-view.tsx"), "utf8");

// The only stubbed modules: the network read client and next/link. Bare specifiers (react/jsx-runtime)
// resolve through the project node_modules because the temp dir lives inside the project.
const apiStub = `
let __summary = { data: undefined, error: undefined };
let __timeline = { data: undefined, error: undefined };
let __view = { data: undefined, error: undefined };
export function __setSummary(f) { __summary = f; }
export function __setTimeline(f) { __timeline = f; }
export function __setView(f) { __view = f; }
export async function getSupportOperationsSummary() { return __summary; }
export async function getSupportOperationsTimeline(_p) { return __timeline; }
export async function getDataRepairOperationsView(_id) { return __view; }
`;

const linkStub = `
import { jsx } from "react/jsx-runtime";
export default function Link({ href, children, ...rest }) {
  return jsx("a", { href, ...rest, children });
}
`;

const overviewJs = transpile(overviewSrc, "support-operations-overview.tsx")
  .replace(/["']@\/lib\/internal-support-operations-api["']/g, '"./api-stub.mjs"')
  .replace(/["']next\/link["']/g, '"./link-stub.mjs"');
const detailJs = transpile(detailSrc, "data-repair-operations-view.tsx")
  .replace(/["']@\/lib\/internal-support-operations-api["']/g, '"./api-stub.mjs"')
  .replace(/["']next\/link["']/g, '"./link-stub.mjs"');

writeFileSync(join(tmpDir, "api-stub.mjs"), apiStub);
writeFileSync(join(tmpDir, "link-stub.mjs"), linkStub);
writeFileSync(join(tmpDir, "support-operations-overview.mjs"), overviewJs);
writeFileSync(join(tmpDir, "data-repair-operations-view.mjs"), detailJs);

const overview = await import(pathToFileURL(join(tmpDir, "support-operations-overview.mjs")).href);
const detail = await import(pathToFileURL(join(tmpDir, "data-repair-operations-view.mjs")).href);
const stub = await import(pathToFileURL(join(tmpDir, "api-stub.mjs")).href);

// Sentinel raw/leak fields the backend must never expose; none may appear in rendered HTML.
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

function summaryFixture(overrides) {
  return {
    tenantId: "11111111-2222-3333-4444-555555555555",
    openIncidents: 2,
    criticalOpenIncidents: 1,
    pendingBreakGlassRequests: 3,
    approvedActiveBreakGlassRequests: 1,
    pendingSupportGrants: 0,
    activeSupportGrants: 4,
    pendingDataRepairApprovals: 2,
    approvedDataRepairRequests: 5,
    executedProcessingJobRepairs: 1,
    rejectedDataRepairRequests: 1,
    latestActivityAt: "2026-06-26T12:00:00Z",
    generatedAt: "2026-06-26T12:00:01Z",
    externalExecution: "DISABLED",
    ...LEAK_DECOYS,
    ...overrides
  };
}

function timelineEntry(overrides) {
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

function timelineFixture(overrides) {
  return {
    tenantId: "11111111-2222-3333-4444-555555555555",
    page: 0,
    pageSize: 20,
    returnedCount: 2,
    hasMore: true,
    entries: [
      timelineEntry({}),
      timelineEntry({ category: "DATA_REPAIR", eventType: "DATA_REPAIR_APPROVED", status: "APPROVED" })
    ],
    generatedAt: "2026-06-26T12:00:01Z",
    ...overrides
  };
}

function viewFixture(overrides) {
  return {
    requestId: "99999999-8888-7777-6666-555555555555",
    tenantId: "11111111-2222-3333-4444-555555555555",
    targetType: "PROCESSING_JOB_STATUS_REPAIR",
    approvalStatus: "APPROVED",
    executionStatus: "EXECUTED",
    dryRunSummary: "DRY_RUN_COMPLETED",
    affectedTargetSummary: "1 stuck processing job",
    processingJobId: "12121212-3434-5656-7878-909090909090",
    previousStatus: "PROCESSING",
    newStatus: "FAILED",
    executedAt: "2026-06-26T12:00:00Z",
    executed: true,
    timeline: [
      timelineEntry({
        category: "PROCESSING_JOB_REPAIR",
        eventType: "PROCESSING_JOB_REPAIR_EXECUTED",
        status: "EXECUTED"
      })
    ],
    generatedAt: "2026-06-26T12:00:01Z",
    externalExecution: "DISABLED",
    ...overrides
  };
}

// --- Summary ---

test("summary renders safe backend-derived counts; no leak; read-only", async () => {
  stub.__setSummary({ data: summaryFixture({}) });
  const html = renderToStaticMarkup(await overview.SupportOperationsSummaryPanel());
  assert.ok(html.includes("Open incidents"));
  assert.ok(html.includes("Executed processing-job repairs"));
  assert.ok(html.includes("DISABLED"));
  assert.ok(html.includes("2026-06-26T12:00:00Z"));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("summary forbidden/error renders a safe mapped message only", async () => {
  stub.__setSummary({ error: "You do not have access to this internal support view." });
  const html = renderToStaticMarkup(await overview.SupportOperationsSummaryPanel());
  assert.ok(html.includes("You do not have access to this internal support view."));
  assert.ok(!html.toLowerCase().includes("stack"));
  assert.ok(!html.toLowerCase().includes("exception"));
  assertReadOnly(html);
});

// --- Timeline ---

test("timeline renders bounded events in safe columns; no leak; read-only of mutation controls", async () => {
  stub.__setTimeline({ data: timelineFixture({}) });
  const html = renderToStaticMarkup(await overview.SupportOperationsTimelinePanel({ page: 0, size: 20 }));
  assert.ok(html.includes("INCIDENT_CREATED"));
  assert.ok(html.includes("DATA_REPAIR_APPROVED"));
  assert.ok(html.includes("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
  assert.ok(html.includes("2 events"));
  assertNoLeak(html);
  // Pager renders anchors only — no form/input/button mutation control.
  assertReadOnly(html);
});

test("timeline empty renders a stable safe empty message", async () => {
  stub.__setTimeline({ data: timelineFixture({ entries: [], returnedCount: 0, hasMore: false }) });
  const html = renderToStaticMarkup(await overview.SupportOperationsTimelinePanel({ page: 0, size: 20 }));
  assert.ok(html.includes("No operations events recorded for this tenant yet."));
  assertReadOnly(html);
});

test("timeline forbidden/error renders a safe mapped message only", async () => {
  stub.__setTimeline({ error: "You do not have access to this internal support view." });
  const html = renderToStaticMarkup(await overview.SupportOperationsTimelinePanel({ page: 0, size: 20 }));
  assert.ok(html.includes("You do not have access to this internal support view."));
  assert.ok(!html.toLowerCase().includes("stacktrace"));
});

// --- Data-repair operations view ---

test("operations-view renders safe request diagnostics; no leak; read-only", async () => {
  stub.__setView({ data: viewFixture({}) });
  const html = renderToStaticMarkup(await detail.DataRepairOperationsView({ requestId: "99999999-8888-7777-6666-555555555555" }));
  assert.ok(html.includes("PROCESSING_JOB_STATUS_REPAIR"));
  assert.ok(html.includes("APPROVED"));
  assert.ok(html.includes("EXECUTED"));
  assert.ok(html.includes("1 stuck processing job"));
  assert.ok(html.includes("PROCESSING_JOB_REPAIR_EXECUTED"));
  assert.ok(html.includes("DISABLED"));
  assertNoLeak(html);
  assertReadOnly(html);
});

test("operations-view forbidden/error renders a safe mapped message only", async () => {
  stub.__setView({ error: "This support operations view was not found for this tenant context." });
  const html = renderToStaticMarkup(await detail.DataRepairOperationsView({ requestId: "missing" }));
  assert.ok(html.includes("This support operations view was not found for this tenant context."));
  assert.ok(!html.toLowerCase().includes("stack"));
  assertReadOnly(html);
});
