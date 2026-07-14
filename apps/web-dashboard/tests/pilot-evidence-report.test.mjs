import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClientPath = join(root, "lib", "pilot-metrics-api.ts");
const reportPagePath = join(root, "app", "(dashboard)", "pilot-readiness", "evidence-report", "page.tsx");
const navPath = join(root, "components", "navigation.ts");

const apiClient = readFileSync(apiClientPath, "utf8");
const reportPage = readFileSync(reportPagePath, "utf8");
const nav = readFileSync(navPath, "utf8");

test("evidence report API client maps the contract and targets the guarded endpoint", () => {
  assert.equal(existsSync(apiClientPath), true);
  assert.match(apiClient, /\/api\/v1\/pilot\/evidence-report/);
  assert.match(apiClient, /getPilotEvidenceReport/);
  assert.match(apiClient, /ANALYTICS_READ/);
  assert.match(apiClient, /dashboardRequestHeaders\(pilotMetricsClient\.tenantId,\s*ANALYTICS_READ\)/);
  // Contract fields are mapped.
  for (const field of [
    "estimatedMinutesSaved",
    "estimatedCostSaved",
    "exceptionBreakdown",
    "topExceptionCategories",
    "readinessSignals",
    "limitations",
    "safetyStatement"
  ]) {
    assert.match(apiClient, new RegExp(field));
  }
  // No raw payload fields in the client contract.
  assert.doesNotMatch(apiClient, /predictionPayloadJson|beforePayloadJson|afterPayloadJson/);
});

test("evidence report page renders loaded, empty, and safety/limitations sections", () => {
  assert.equal(existsSync(reportPagePath), true);
  // Loaded state content.
  assert.match(reportPage, /ROI: estimated savings/);
  assert.match(reportPage, /Cycle time/);
  assert.match(reportPage, /Exception category breakdown/);
  assert.match(reportPage, /Readiness signals/);
  assert.match(reportPage, /Automation candidates/);
  assert.match(reportPage, /Review workload/);
  // Empty state.
  assert.match(reportPage, /No pilot evidence yet/);
  // Safety / limitations panel.
  assert.match(reportPage, /Safety &amp; limitations/);
  assert.match(reportPage, /not a guarantee of production ROI/);
  // No raw payload exposure in the UI.
  assert.doesNotMatch(reportPage, /predictionPayloadJson|beforePayloadJson|afterPayloadJson/);
});

test("navigation exposes the pilot evidence report route", () => {
  assert.match(nav, /\/pilot-readiness\/evidence-report/);
});
