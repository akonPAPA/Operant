// PR #251 — Commerce Intelligence RUNTIME RENDER PROOF.
//
// This is a genuine render test, not source inspection: it compiles the REAL PR #245 view component
// (`components/commerce-intelligence-demo-flow.tsx`) with the installed TypeScript compiler (no new
// dependencies), stubs ONLY `next/link` with a plain anchor, then renders each operator-facing state
// with `react-dom/server` and asserts on the produced HTML.
//
// It proves the operator-facing states — populated (summary cards + safety + runtime posture +
// bottlenecks + recent flows), safe empty state, and safe backend-error state — and proves the data
// boundary at render time: decoy internal/raw fields attached to the fixture (tenantId, actorId,
// idempotencyKey, rawPayload, prompt, secret, connectionId) NEVER reach the HTML, no raw JSON object
// is dumped, and the error surface never shows a raw backend body / stack trace.

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";

// Resolve the frontend app root relative to THIS test file, not process.cwd(), so the test reads the
// real component from the same absolute path whether it is launched from the repo root
// (`node --test apps/web-dashboard/tests/...`) or from `apps/web-dashboard` (`node --test tests/...`).
const testDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(testDir, "..");

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

// Compile the real component into a temp dir INSIDE the project so bare specifiers
// (`react/jsx-runtime`) resolve through node_modules. The type-only import from the API client is
// erased by the transpile; the only stubbed module is `next/link`.
const tmpDir = mkdtempSync(join(root, ".pr251-render-"));

const componentSrc = readFileSync(
  join(root, "components", "commerce-intelligence-demo-flow.tsx"),
  "utf8"
);

const componentJs = transpile(componentSrc, "commerce-intelligence-demo-flow.tsx").replace(
  /["']next\/link["']/g,
  '"./link-stub.mjs"'
);

const linkStub = `
import { jsx } from "react/jsx-runtime";
export default function Link({ href, children, ...rest }) {
  return jsx("a", { href, ...rest, children });
}
`;

writeFileSync(join(tmpDir, "link-stub.mjs"), linkStub);
writeFileSync(join(tmpDir, "commerce-intelligence-demo-flow.mjs"), componentJs);

const componentUrl = pathToFileURL(join(tmpDir, "commerce-intelligence-demo-flow.mjs")).href;
const { CommerceIntelligenceDemoFlowView } = await import(componentUrl);

test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

const SENTINELS = [
  "SENTINEL_TENANT_ID",
  "SENTINEL_ACTOR_ID",
  "SENTINEL_IDEMPOTENCY_KEY",
  "SENTINEL_RAW_PAYLOAD",
  "SENTINEL_PROMPT",
  "SENTINEL_SECRET",
  "SENTINEL_CONNECTION_ID"
];

function assertNoLeak(html) {
  for (const s of SENTINELS) {
    assert.ok(!html.includes(s), `leaked internal/raw field into rendered HTML: ${s}`);
  }
  // No raw JSON dump of the response object and no raw response keys.
  assert.ok(!html.includes("tenantId"), "rendered HTML must not contain raw response keys");
  assert.ok(!html.includes("idempotencyKey"), "rendered HTML must not contain raw response keys");
}

// Decoy internal/raw fields are intentionally attached to every fixture to prove the component never
// blindly dumps the object — none of these sentinels may appear in the rendered HTML.
const DECOYS = {
  tenantId: "SENTINEL_TENANT_ID",
  actorId: "SENTINEL_ACTOR_ID",
  idempotencyKey: "SENTINEL_IDEMPOTENCY_KEY",
  rawPayload: "SENTINEL_RAW_PAYLOAD",
  prompt: "SENTINEL_PROMPT",
  secret: "SENTINEL_SECRET",
  channelConnectionId: "SENTINEL_CONNECTION_ID"
};

function safety() {
  return {
    externalWriteStatus: "DISABLED",
    connectorCallStatus: "NOT_INVOKED",
    outboxStatus: "NOT_REQUESTED",
    observedConnectorCommandRows: null,
    observedChangeRequestRows: null,
    observedOutboxRows: null,
    measurementScope: "NOT_MEASURED",
    safetyStatement:
      "The demo workflow contract disables external writes. External-row counts are not measured.",
    notProven: ["Connector command rows are not measured."]
  };
}

function runtimeControl() {
  return {
    guarded: true,
    demoRfqHandoffCreate: "RATE_BACKPRESSURE_GATED",
    rfqHandoffAiAdvisory: "AI_VALIDATION_EXPLANATION_GUARDED",
    draftQuoteCreate: "RATE_BACKPRESSURE_GATED",
    safeDemoDecision: "RATE_BACKPRESSURE_GATED",
    billingOrQuotaDimension: "NOT_APPLICABLE_FOR_DEMO_OPS",
    denialTelemetry: "NOT_MEASURED",
    note: "PR #244 guards the write and advisory boundaries. This read model does not invoke the guard."
  };
}

function populated(overrides = {}) {
  return {
    generatedAt: "2026-07-05T12:00:00Z",
    windowLabel: "Tenant-observed demo flow (all retained records)",
    summary: {
      rfqHandoffsTotal: 4,
      pendingReviewCount: 1,
      inReviewCount: 1,
      convertedCount: 1,
      dismissedCount: 1,
      aiAdvisorySuggestionsCount: 3,
      reviewRequiredDraftQuotesCount: 2,
      safeTerminalDemoDecisionsCount: 1,
      demoCompletedCount: 1,
      demoDeclinedCount: 0
    },
    safety: safety(),
    runtimeControl: runtimeControl(),
    bottlenecks: [
      {
        code: "PRICE_NOT_RESOLVED",
        label: "Price not resolved",
        count: 1,
        severity: "BLOCKING",
        explanation: "An open blocking validation issue requires operator review."
      }
    ],
    recentFlows: [
      {
        handoffId: "11111111-2222-3333-4444-555555555555",
        sourceChannel: "TELEGRAM",
        requestPreview: "Please quote 4x brake pads",
        detectedIntent: "RFQ_REQUEST",
        handoffStatus: "CONVERTED",
        aiSuggestionStatus: "SUGGESTED",
        aiSchemaVersion: "AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION",
        aiRiskLevel: "MEDIUM",
        draftQuoteStatus: "DEMO_COMPLETED",
        validationStatus: "NEEDS_REVIEW",
        safeTerminalState: "SAFE_DEMO_TERMINAL",
        blockingIssueCodes: ["PRICE_NOT_RESOLVED"],
        createdAt: "2026-07-05T11:55:00Z",
        updatedAt: "2026-07-05T11:58:00Z",
        ...DECOYS
      }
    ],
    notProven: [
      {
        code: "PRODUCTION_CONVERSION_NOT_MEASURED",
        label: "Production conversion",
        explanation: "Demo completion is not a real order, sale, revenue event, invoice, or ERP sync."
      }
    ],
    ...DECOYS,
    ...overrides
  };
}

function render(props) {
  return renderToStaticMarkup(CommerceIntelligenceDemoFlowView(props));
}

test("STATE populated — summary cards, safety, runtime posture, bottlenecks, recent flows render", () => {
  const html = render({ data: populated() });

  // Summary cards.
  for (const label of [
    "RFQs captured",
    "Pending review",
    "In review",
    "AI advisory suggestions",
    "Review-required draft quotes",
    "Safe demo terminal decisions"
  ]) {
    assert.ok(html.includes(label), `summary card missing: ${label}`);
  }

  // Safety posture — clear read-only / no external execution labels.
  assert.ok(html.includes("Safety state"));
  assert.ok(
    html.includes("Read-only intelligence · external execution disabled · no connector invoked"),
    "explicit safety caption must render"
  );
  assert.ok(html.includes("DISABLED"), "external write DISABLED must render");
  assert.ok(html.includes("NOT_INVOKED"), "connector NOT_INVOKED must render");
  assert.ok(html.includes("NOT_REQUESTED"), "change request / outbox NOT_REQUESTED must render");

  // Runtime-control posture.
  assert.ok(html.includes("Runtime-control posture"));
  assert.ok(html.includes("rate backpressure gated"));

  // Bottlenecks + recent flows.
  assert.ok(html.includes("Blocking bottlenecks"));
  assert.ok(html.includes("Price not resolved"));
  assert.ok(html.includes("Recent demo flows"));
  assert.ok(html.includes("Please quote 4x brake pads"), "safe request preview renders");
  assert.ok(html.includes("safe demo terminal"), "humanized safe-terminal label renders");

  // Explicit not-proven honesty section.
  assert.ok(html.includes("Not proven by this read model"));

  assertNoLeak(html);
});

test("STATE populated — no raw JSON dump and no <pre> object rendering", () => {
  const html = render({ data: populated() });
  assert.ok(!html.includes("<pre"), "must not dump raw objects in a <pre>");
  assert.ok(!/SENTINEL_/.test(html), "no decoy sentinel may render");
});

test("STATE empty — safe empty messages, honest zeros, no fake metrics", () => {
  const html = render({
    data: populated({
      summary: {
        rfqHandoffsTotal: 0,
        pendingReviewCount: 0,
        inReviewCount: 0,
        convertedCount: 0,
        dismissedCount: 0,
        aiAdvisorySuggestionsCount: 0,
        reviewRequiredDraftQuotesCount: 0,
        safeTerminalDemoDecisionsCount: 0,
        demoCompletedCount: 0,
        demoDeclinedCount: 0
      },
      bottlenecks: [],
      recentFlows: []
    })
  });

  assert.ok(
    html.includes("No open blocking issues were observed for RFQ-handoff draft quotes."),
    "safe empty bottleneck message"
  );
  assert.ok(
    html.includes("No RFQ handoff flows are available for this tenant."),
    "safe empty recent-flow message"
  );
  // Honest zeros still render as summary cards (not hidden, not faked).
  assert.ok(html.includes("RFQs captured"));
  assertNoLeak(html);
});

test("STATE unavailable — null data shows safe fallback with links, no raw error", () => {
  const html = render({ data: null });
  assert.ok(html.includes("Commerce Intelligence unavailable"));
  assert.ok(
    html.includes("No tenant-observed demo-flow data is available."),
    "safe default copy when no error string is provided"
  );
  assert.ok(html.includes('href="/demo"'));
  assert.ok(html.includes('href="/channels/rfq-handoffs"'));
});

test("STATE backend error — mapped message only, no raw stack / JSON / backend body", () => {
  const html = render({
    data: null,
    error: "Commerce Intelligence is temporarily unavailable. Please try again shortly."
  });
  assert.ok(html.includes("Commerce Intelligence is temporarily unavailable. Please try again shortly."));
  assert.ok(!html.includes("{"), "no raw JSON object rendered");
  assert.ok(
    !/stack|Exception|SQLException|at [\w.$]+\([\w.]+:\d+\)/i.test(html),
    "no stack trace / raw backend exception rendered"
  );
});

test("STATE partial error banner — data plus a soft error string renders both safely", () => {
  const html = render({ data: populated(), error: "Some data may be stale." });
  assert.ok(html.includes("Some data may be stale."));
  assert.ok(html.includes("RFQs captured"), "populated content still renders");
  assertNoLeak(html);
});
