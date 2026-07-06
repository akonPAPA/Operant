// PR #252 — Runtime Control Telemetry RENDER PROOF (RFQ/AI/demo path).
//
// A genuine render test (not source inspection): it compiles the REAL view component
// (`components/runtime-control-telemetry-panel.tsx`) with the installed TypeScript compiler (no new
// dependencies), stubs ONLY `next/link` with a plain anchor, then renders each operator-facing state
// with `react-dom/server` and asserts on the produced HTML.
//
// It proves the operator-facing states — populated (safety posture + per-step workload posture +
// admission posture with honest measured/static/NOT_MEASURED/NOT_APPLICABLE cells + proven guarantees +
// not-measured section), safe empty state, safe unavailable state, and safe backend-error state — and
// proves the data boundary at render time: decoy internal/raw fields attached to the fixture (tenantId,
// actorId, idempotencyKey, rawPayload, prompt, secret, connectionId) NEVER reach the HTML, no raw JSON
// object is dumped, and the error surface never shows a raw backend body / stack trace.

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";

// Resolve the frontend app root relative to THIS test file, not process.cwd(), so the test reads the
// real component from the same absolute path whether launched from the repo root or from apps/web-dashboard.
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

const tmpDir = mkdtempSync(join(root, ".pr252-render-"));

const componentSrc = readFileSync(
  join(root, "components", "runtime-control-telemetry-panel.tsx"),
  "utf8"
);

const componentJs = transpile(componentSrc, "runtime-control-telemetry-panel.tsx").replace(
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
writeFileSync(join(tmpDir, "runtime-control-telemetry-panel.mjs"), componentJs);

const componentUrl = pathToFileURL(join(tmpDir, "runtime-control-telemetry-panel.mjs")).href;
const { RuntimeControlTelemetryPanel } = await import(componentUrl);

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
  assert.ok(!html.includes("tenantId"), "rendered HTML must not contain raw response keys");
  assert.ok(!html.includes("idempotencyKey"), "rendered HTML must not contain raw response keys");
}

// Decoy internal/raw fields attached to every fixture to prove the component never blindly dumps the
// object — none of these sentinels may appear in the rendered HTML.
const DECOYS = {
  tenantId: "SENTINEL_TENANT_ID",
  actorId: "SENTINEL_ACTOR_ID",
  idempotencyKey: "SENTINEL_IDEMPOTENCY_KEY",
  rawPayload: "SENTINEL_RAW_PAYLOAD",
  prompt: "SENTINEL_PROMPT",
  secret: "SENTINEL_SECRET",
  channelConnectionId: "SENTINEL_CONNECTION_ID"
};

function staticContract(value, explanation) {
  return { kind: "STATIC_CONTRACT", value, explanation };
}
function notMeasured(explanation) {
  return { kind: "NOT_MEASURED", value: null, explanation };
}
function notApplicable(explanation) {
  return { kind: "NOT_APPLICABLE", value: null, explanation };
}

function populated(overrides = {}) {
  return {
    generatedAt: "2026-07-05T12:00:00Z",
    scopeLabel: "Runtime-control posture for the RFQ/AI/demo path only",
    safety: {
      runtimeControlView: "READ_ONLY",
      connectorInvocation: "NOT_INVOKED",
      externalExecution: "DISABLED",
      guardEvaluation: "NOT_INVOKED_BY_THIS_READ",
      telemetryCompleteness: "PARTIAL",
      statement: "Read-only runtime-control posture. Telemetry is partial.",
      ...DECOYS
    },
    workloadPostures: [
      {
        pathStep: "DEMO_RFQ_HANDOFF_CREATE",
        label: "Demo RFQ handoff creation",
        workloadType: staticContract("DETERMINISTIC_DEMO_OP", "Not an AI workload."),
        executionPosture: staticContract("SYNC", "Runs synchronously."),
        costPath: staticContract("CHEAP_PATH", "No provider call."),
        guardPosture: staticContract("RATE_BACKPRESSURE_GATED", "Rate + backpressure only."),
        ...DECOYS
      },
      {
        pathStep: "RFQ_HANDOFF_AI_ADVISORY",
        label: "RFQ handoff AI advisory suggestion",
        workloadType: staticContract("AI_VALIDATION_ASSIST", "Advisory AI workload."),
        executionPosture: staticContract("SYNC_WITH_ASYNC_PROMOTION", "May promote to async."),
        costPath: staticContract("AI_PATH", "Reaches provider boundary."),
        guardPosture: staticContract("ENTITLEMENT_QUOTA_RATE_GATED", "Full guard."),
        ...DECOYS
      }
    ],
    admission: {
      runtimeControlEnabled: staticContract("ENABLED", "Contract default."),
      aiWorkloadEnabled: staticContract("ENABLED", "Contract default."),
      maxCostUnitsPerRequest: staticContract("10000", "Per-request ceiling."),
      maxSyncCostUnits: staticContract("100", "Sync ceiling."),
      backpressureQueueDepth: staticContract("1000", "Queue depth."),
      admittedCount: notMeasured("Admitted counts are not persisted."),
      deniedCount: notMeasured("Denied counts are not persisted."),
      ...DECOYS
    },
    provenGuarantees: [
      {
        code: "ALL_DEMO_CHECKPOINTS_GUARDED",
        label: "All four RFQ/AI/demo checkpoints are guarded",
        statement: "PR #244 guards demo writes and the advisory boundary."
      }
    ],
    notMeasured: [
      {
        code: "RUNTIME_DENIAL_TELEMETRY_NOT_MEASURED",
        label: "Runtime denial/admission counts",
        explanation: "Not persisted; labelled NOT_MEASURED, never a fake zero."
      }
    ],
    ...DECOYS,
    ...overrides
  };
}

function render(props) {
  return renderToStaticMarkup(RuntimeControlTelemetryPanel(props));
}

test("STATE populated — safety, workload posture, admission, guarantees render with honest labels", () => {
  const html = render({ data: populated() });

  assert.ok(html.includes("Runtime-control telemetry (RFQ/AI/demo path)"));
  assert.ok(
    html.includes(
      "Read-only runtime-control view · no connector invoked · external execution disabled · telemetry"
    ),
    "explicit safety caption must render"
  );

  // Safety posture states.
  assert.ok(html.includes("READ_ONLY"));
  assert.ok(html.includes("NOT_INVOKED"));
  assert.ok(html.includes("DISABLED"));
  assert.ok(html.includes("PARTIAL"));

  // Per-step workload posture (measured/static cells are humanized).
  assert.ok(html.includes("Path-step runtime posture"));
  assert.ok(html.includes("Demo RFQ handoff creation"));
  assert.ok(html.includes("deterministic demo op"));
  assert.ok(html.includes("rate backpressure gated"));
  assert.ok(html.includes("ai path"), "AI cost path humanized label renders");

  // Admission posture with honest NOT_MEASURED counters.
  assert.ok(html.includes("Admission &amp; backpressure posture"));
  assert.ok(html.includes("Denied requests"));
  assert.ok(html.includes("Not measured"), "NOT_MEASURED counters render an honest label, not a zero");

  // Proven guarantees + not-measured honesty sections.
  assert.ok(html.includes("Runtime-control guarantees proven for the demo path"));
  assert.ok(html.includes("Not measured by this read model"));

  assertNoLeak(html);
});

test("STATE populated — no raw JSON dump and no <pre> object rendering", () => {
  const html = render({ data: populated() });
  assert.ok(!html.includes("<pre"), "must not dump raw objects in a <pre>");
  assert.ok(!/SENTINEL_/.test(html), "no decoy sentinel may render");
});

test("STATE not-applicable — a NOT_APPLICABLE cell renders an honest label, not a value", () => {
  const html = render({
    data: populated({
      admission: {
        runtimeControlEnabled: staticContract("ENABLED", "Contract default."),
        aiWorkloadEnabled: staticContract("ENABLED", "Contract default."),
        maxCostUnitsPerRequest: staticContract("10000", "Per-request ceiling."),
        maxSyncCostUnits: staticContract("100", "Sync ceiling."),
        backpressureQueueDepth: staticContract("1000", "Queue depth."),
        admittedCount: notMeasured("Admitted counts are not persisted."),
        deniedCount: notApplicable("Billing/quota does not apply to rate-only demo ops.")
      }
    })
  });
  assert.ok(html.includes("Not applicable"), "NOT_APPLICABLE renders an honest label");
  assertNoLeak(html);
});

test("STATE empty — no path steps renders safe empty message", () => {
  const html = render({ data: populated({ workloadPostures: [], provenGuarantees: [] }) });
  assert.ok(
    html.includes("No runtime-control path steps are described for this tenant."),
    "safe empty workload message"
  );
  assert.ok(
    html.includes("No runtime-control guarantees are recorded for this tenant."),
    "safe empty guarantees message"
  );
  assertNoLeak(html);
});

test("STATE unavailable — null data shows safe fallback with links, no raw error", () => {
  const html = render({ data: null });
  assert.ok(html.includes("Runtime-control telemetry unavailable"));
  assert.ok(
    html.includes("No tenant-observed runtime-control telemetry is available."),
    "safe default copy when no error string is provided"
  );
  assert.ok(html.includes('href="/commerce-intelligence"'));
  assert.ok(html.includes('href="/channels/rfq-handoffs"'));
});

test("STATE backend error — mapped message only, no raw stack / JSON / backend body", () => {
  const html = render({
    data: null,
    error: "Runtime-control telemetry is temporarily unavailable. Please try again shortly."
  });
  assert.ok(
    html.includes("Runtime-control telemetry is temporarily unavailable. Please try again shortly.")
  );
  assert.ok(!html.includes("{"), "no raw JSON object rendered");
  assert.ok(
    !/stack|Exception|SQLException|at [\w.$]+\([\w.]+:\d+\)/i.test(html),
    "no stack trace / raw backend exception rendered"
  );
});

test("STATE partial error banner — data plus a soft error string renders both safely", () => {
  const html = render({ data: populated(), error: "Some telemetry may be stale." });
  assert.ok(html.includes("Some telemetry may be stale."));
  assert.ok(html.includes("Path-step runtime posture"), "populated content still renders");
  assertNoLeak(html);
});
