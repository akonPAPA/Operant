// PR #253 — Runtime Control Telemetry API-CLIENT HARDENING PROOF.
//
// Compiles the REAL client (`lib/runtime-control-telemetry-api.ts`) with the installed TypeScript
// compiler (no new dependencies), stubs ONLY the tenant-authority module, and drives it with a mocked
// global `fetch`. It proves the client cleanly separates network failure, non-2xx HTTP, malformed JSON,
// and response-contract drift into bounded safe messages, and that a hostile/raw backend body is NEVER
// echoed back to the UI.

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";

const testDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(testDir, "..");

function transpile(source, fileName) {
  return ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022
    },
    fileName
  }).outputText;
}

const tmpDir = mkdtempSync(join(root, ".pr253-api-"));

const clientSrc = readFileSync(join(root, "lib", "runtime-control-telemetry-api.ts"), "utf8");
const clientJs = transpile(clientSrc, "runtime-control-telemetry-api.ts")
  .replace(/["']\.\/frontend-authority\.mjs["']/g, '"./authority-stub.mjs"')
  .replace(/["']\.\/api-transport["']/g, '"./api-transport-stub.mjs"');

const authorityStub = `export function demoTenantId() { return "11111111-1111-1111-1111-111111111111"; }\n`;
const apiTransportStub = `export function dashboardCoreApiBaseUrl() { return "http://localhost:8080"; }\n`;

writeFileSync(join(tmpDir, "authority-stub.mjs"), authorityStub);
writeFileSync(join(tmpDir, "api-transport-stub.mjs"), apiTransportStub);
writeFileSync(join(tmpDir, "runtime-control-telemetry-api.mjs"), clientJs);

const clientUrl = pathToFileURL(join(tmpDir, "runtime-control-telemetry-api.mjs")).href;
const { getRuntimeControlDemoFlowTelemetry } = await import(clientUrl);

test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

function cell(kind, value, explanation) {
  return { kind, value, explanation };
}

function validBody() {
  return {
    generatedAt: "2026-07-05T12:00:00Z",
    scopeLabel: "Runtime-control default contract posture for the RFQ/AI/demo path (tenant-gated read)",
    safety: {
      runtimeControlView: "READ_ONLY",
      connectorInvocation: "NOT_INVOKED",
      externalExecution: "DISABLED",
      guardEvaluation: "NOT_INVOKED_BY_THIS_READ",
      telemetryCompleteness: "PARTIAL",
      statement: "Read-only, tenant-gated runtime-control posture."
    },
    workloadPostures: [
      {
        pathStep: "DEMO_RFQ_HANDOFF_CREATE",
        label: "Demo RFQ handoff creation",
        workloadType: cell("STATIC_CONTRACT", "DETERMINISTIC_DEMO_OP", "Not an AI workload."),
        executionPosture: cell("STATIC_CONTRACT", "SYNC", "Runs synchronously."),
        costPath: cell("STATIC_CONTRACT", "CHEAP_PATH", "No provider call."),
        guardPosture: cell("STATIC_CONTRACT", "RATE_BACKPRESSURE_GATED", "Rate + backpressure only.")
      }
    ],
    admission: {
      runtimeControlEnabled: cell("STATIC_CONTRACT", "ENABLED", "Contract default."),
      aiWorkloadEnabled: cell("STATIC_CONTRACT", "ENABLED", "Contract default."),
      maxCostUnitsPerRequest: cell("STATIC_CONTRACT", "10000", "Per-request ceiling."),
      maxSyncCostUnits: cell("STATIC_CONTRACT", "100", "Sync ceiling."),
      backpressureQueueDepth: cell("STATIC_CONTRACT", "1000", "Queue depth."),
      admittedCount: cell("NOT_MEASURED", null, "Not persisted."),
      deniedCount: cell("NOT_MEASURED", null, "Not persisted.")
    },
    provenGuarantees: [{ code: "ALL_DEMO_CHECKPOINTS_GUARDED", label: "Guarded", statement: "PR #244." }],
    notMeasured: [{ code: "TENANT_RATE_BUCKET_STATE_NOT_MEASURED", label: "Rate", explanation: "n/a" }]
  };
}

function fetchReturning({ ok = true, status = 200, body = "" }) {
  return async () => ({ ok, status, text: async () => body });
}

function fetchThrowing() {
  return async () => {
    throw new Error("network down");
  };
}

test("200 + valid response returns data", async () => {
  globalThis.fetch = fetchReturning({ body: JSON.stringify(validBody()) });
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.error, undefined);
  assert.equal(result.data.safety.runtimeControlView, "READ_ONLY");
});

test("200 + malformed JSON returns invalid-response message", async () => {
  globalThis.fetch = fetchReturning({ body: "{ this is not json" });
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.data, null);
  assert.equal(result.error, "Runtime-control telemetry response is invalid.");
});

test("200 + missing safety returns invalid-contract message", async () => {
  const body = validBody();
  delete body.safety;
  globalThis.fetch = fetchReturning({ body: JSON.stringify(body) });
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.data, null);
  assert.equal(result.error, "Runtime-control telemetry contract is invalid.");
});

test("200 + unknown measurement kind returns invalid-contract message", async () => {
  const body = validBody();
  body.admission.deniedCount = cell("MYSTERY_KIND", null, "bogus");
  globalThis.fetch = fetchReturning({ body: JSON.stringify(body) });
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.data, null);
  assert.equal(result.error, "Runtime-control telemetry contract is invalid.");
});

test("403 returns the mapped access message", async () => {
  globalThis.fetch = fetchReturning({ ok: false, status: 403, body: "forbidden raw body" });
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.data, null);
  assert.equal(result.error, "You do not have access to runtime-control telemetry.");
});

test("fetch throwing returns the not-reachable message", async () => {
  globalThis.fetch = fetchThrowing();
  const result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.data, null);
  assert.equal(result.error, "Core API is not reachable.");
});

test("a hostile raw backend body is never echoed as the error text", async () => {
  const hostile =
    '{"tenantId":"11111111","actorId":"22222222","trace":"java.sql.SQLException at Foo.bar(Foo.java:42) stack"';
  // Case A: malformed JSON body carrying the hostile sentinels -> bounded invalid-response message only.
  globalThis.fetch = fetchReturning({ body: hostile });
  let result = await getRuntimeControlDemoFlowTelemetry();
  assert.equal(result.error, "Runtime-control telemetry response is invalid.");

  // Case B: a 500 with a nasty raw body -> mapped status message only, raw body discarded.
  globalThis.fetch = fetchReturning({
    ok: false,
    status: 500,
    body: '{"stack":"SQLException","tenantId":"leak"}'
  });
  const serverResult = await getRuntimeControlDemoFlowTelemetry();

  for (const bad of ["SQLException", "tenantId", "actorId", "stack", "Foo.java", "{"]) {
    assert.ok(!String(result.error).includes(bad), `error text leaked: ${bad}`);
    assert.ok(!String(serverResult.error).includes(bad), `error text leaked: ${bad}`);
  }
});
