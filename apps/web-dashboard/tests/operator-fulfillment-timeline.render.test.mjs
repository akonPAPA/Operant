// OP-CAP-47C — Operator Fulfillment Timeline RUNTIME RENDER PROOF.
//
// This is a genuine render test, not source inspection: it compiles the REAL OP-CAP-47B component
// (`components/operator-fulfillment-timeline.tsx`) and its real badge dependency with the installed
// TypeScript compiler (no new dependencies), stubs ONLY the network read with controlled fixtures,
// then renders each operator-facing state with `react-dom/server` and asserts on the produced HTML.
//
// It proves the four states operators need — empty, return-requested, multi-signal (backend-ordered),
// and loading/error — and proves the safety boundary at render time: internal/raw fields placed on the
// fixture (journeyId, payloadRef, idempotencyKey, tenantId, sourceId, sourceRef) NEVER reach the HTML,
// and there is no form/input/button/mutation control on this read-only surface.

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

// Compile the real component + its real dependency into a temp dir INSIDE the project so bare
// specifiers (`react/jsx-runtime`) resolve through node_modules. The only stubbed module is the
// network read client.
const tmpDir = mkdtempSync(join(root, ".op47c-render-"));

const badgeSrc = readFileSync(join(root, "components", "order-journey-status-badge.tsx"), "utf8");
const componentSrc = readFileSync(join(root, "components", "operator-fulfillment-timeline.tsx"), "utf8");

const componentJs = transpile(componentSrc, "operator-fulfillment-timeline.tsx")
  .replace(/["']@\/lib\/order-journey-api["']/g, '"./api-stub.mjs"')
  .replace(/["']\.\/order-journey-status-badge["']/g, '"./order-journey-status-badge.mjs"');

const apiStub = `
let __fixture = { data: null, error: undefined };
export function __setFixture(f) { __fixture = f; }
export async function getOperatorFulfillmentTimeline(_id) { return __fixture; }
`;

writeFileSync(join(tmpDir, "order-journey-status-badge.mjs"), transpile(badgeSrc, "badge.tsx"));
writeFileSync(join(tmpDir, "api-stub.mjs"), apiStub);
writeFileSync(join(tmpDir, "operator-fulfillment-timeline.mjs"), componentJs);

const componentUrl = pathToFileURL(join(tmpDir, "operator-fulfillment-timeline.mjs")).href;
const stubUrl = pathToFileURL(join(tmpDir, "api-stub.mjs")).href;

const { OperatorFulfillmentTimeline, OperatorFulfillmentTimelineSkeleton } = await import(componentUrl);
const { __setFixture } = await import(stubUrl);

test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

// A safe baseline journey summary. Decoy internal/raw fields are intentionally attached to prove the
// component never blindly dumps the object — none of these sentinels may appear in the rendered HTML.
const JOURNEY_ID = "11111111-2222-3333-4444-555555555555";
function summary(overrides) {
  return {
    journeyId: JOURNEY_ID,
    sourceType: "ORDER",
    currentStage: "FULFILLMENT",
    currentStatus: "IN_TRANSIT",
    riskLevel: "LOW",
    blocked: false,
    signalCount: 0,
    latestSignalReceivedAt: null,
    returnRequested: false,
    timeline: [],
    createdAt: "2026-06-20T10:00:00Z",
    updatedAt: "2026-06-26T08:00:00Z",
    generatedAt: "2026-06-26T08:00:01Z",
    // --- decoy internal/raw fields the backend must never expose; must NOT render ---
    payloadRef: "SENTINEL_PAYLOAD_REF",
    idempotencyKey: "SENTINEL_IDEMPOTENCY_KEY",
    tenantId: "SENTINEL_TENANT_ID",
    sourceId: "SENTINEL_SOURCE_ID",
    sourceRef: "SENTINEL_SOURCE_REF",
    ...overrides
  };
}

function entry(seq, overrides) {
  return {
    sequence: seq,
    type: "FULFILLMENT_SIGNAL",
    label: `Signal ${seq}`,
    status: "RECEIVED",
    sourceType: "INTERNAL",
    evidenceLevel: "VERIFIED",
    customerVisible: true,
    receivedAt: `2026-06-2${seq}T10:00:00Z`,
    processedAt: null,
    // decoy per-entry internal field
    sourceRef: "SENTINEL_ENTRY_SOURCE_REF",
    ...overrides
  };
}

async function renderTimeline(fixture) {
  __setFixture(fixture);
  const element = await OperatorFulfillmentTimeline({ id: "journey-1" });
  return renderToStaticMarkup(element);
}

const SENTINELS = [
  JOURNEY_ID,
  "SENTINEL_PAYLOAD_REF",
  "SENTINEL_IDEMPOTENCY_KEY",
  "SENTINEL_TENANT_ID",
  "SENTINEL_SOURCE_ID",
  "SENTINEL_SOURCE_REF",
  "SENTINEL_ENTRY_SOURCE_REF"
];

function assertNoLeak(html) {
  for (const s of SENTINELS) {
    assert.ok(!html.includes(s), `leaked internal/raw field into rendered HTML: ${s}`);
  }
  // No raw JSON dump of the response object.
  assert.ok(!html.includes("journeyId"), "rendered HTML must not contain raw response keys");
}

function assertReadOnly(html) {
  for (const ctrl of ["<form", "<input", "<textarea", "<select", "<button"]) {
    assert.ok(!html.includes(ctrl), `read-only surface must not render ${ctrl}`);
  }
}

test("STATE empty — no signals shows the safe empty message, no unsafe fields", async () => {
  const html = await renderTimeline({ data: summary({ signalCount: 0, timeline: [] }) });
  assert.ok(html.includes("No fulfillment signals recorded for this journey yet."));
  assert.ok(html.includes("Fulfillment timeline"));
  assert.ok(html.includes("Current status"));
  assert.ok(!html.includes("Return requested"));
  assertReadOnly(html);
  assertNoLeak(html);
});

test("STATE return requested — attention badge + note, still read-only", async () => {
  const html = await renderTimeline({
    data: summary({ returnRequested: true, signalCount: 1, timeline: [entry(1, { label: "Return requested by customer" })] })
  });
  assert.ok(html.includes("Return requested"), "return-requested badge must show");
  assert.ok(html.includes("A return has been requested on this order."), "attention note must show");
  assertReadOnly(html); // no mutation/approval/execution control added
  assertNoLeak(html);
});

test("STATE multi-signal — rendered strictly in backend sequence order with safe labels", async () => {
  // Provide entries OUT OF ORDER (3,1,2) to prove the component orders by backend sequence.
  const html = await renderTimeline({
    data: summary({
      signalCount: 3,
      latestSignalReceivedAt: "2026-06-23T10:00:00Z",
      timeline: [
        entry(3, { label: "Delivered", status: "COMPLETED", sourceType: "CONNECTOR_MIRROR", evidenceLevel: "MIRRORED", customerVisible: false, receivedAt: "2026-06-23T10:00:00Z", processedAt: "2026-06-23T10:05:00Z" }),
        entry(1, { label: "Picked", status: "RECEIVED", sourceType: "INTERNAL", evidenceLevel: "VERIFIED", customerVisible: true, receivedAt: "2026-06-21T10:00:00Z" }),
        entry(2, { label: "Shipped", status: "ACTIVE", sourceType: "MANUAL", evidenceLevel: "MANUAL", customerVisible: true, receivedAt: "2026-06-22T10:00:00Z" })
      ]
    })
  });
  const iPicked = html.indexOf("Picked");
  const iShipped = html.indexOf("Shipped");
  const iDelivered = html.indexOf("Delivered");
  assert.ok(iPicked >= 0 && iShipped >= 0 && iDelivered >= 0, "all labels render");
  assert.ok(iPicked < iShipped && iShipped < iDelivered, "entries ordered by backend sequence (1,2,3)");
  // Safe per-entry labels render (status/source/evidence/customer-visible/internal).
  assert.ok(html.includes("COMPLETED") && html.includes("RECEIVED") && html.includes("ACTIVE"));
  assert.ok(html.includes("INTERNAL") && html.includes("MANUAL") && html.includes("CONNECTOR_MIRROR"));
  assert.ok(html.includes("Verified") && html.includes("Mirrored") && html.includes("Operator entered"));
  assert.ok(html.includes("Customer-visible") && html.includes("Internal only"));
  // Timestamps render as the given safe ISO strings (received + processed where present).
  assert.ok(html.includes("2026-06-21T10:00:00Z"), "receivedAt renders");
  assert.ok(html.includes("Processed 2026-06-23T10:05:00Z"), "processedAt renders when present");
  assertReadOnly(html);
  assertNoLeak(html);
});

test("STATE loading — Suspense skeleton renders a stable busy placeholder", () => {
  const html = renderToStaticMarkup(OperatorFulfillmentTimelineSkeleton());
  assert.ok(html.includes("Loading fulfillment timeline"));
  assert.ok(html.includes('aria-busy="true"'));
  assertReadOnly(html);
});

test("STATE error — safe message only, no raw backend body / JSON / stack", async () => {
  const html = await renderTimeline({ data: null, error: "Core API returned 500." });
  assert.ok(html.includes("Core API returned 500."), "safe mapped error message renders");
  assert.ok(!html.includes("{"), "no raw JSON object rendered");
  assert.ok(!/stack|Exception|at \w+\.\w+/i.test(html), "no stack trace rendered");
  assertReadOnly(html);
});

test("STATE error fallback — null data + no message uses the safe default copy", async () => {
  const html = await renderTimeline({ data: null });
  assert.ok(html.includes("Fulfillment timeline is unavailable right now."));
  assertReadOnly(html);
});
