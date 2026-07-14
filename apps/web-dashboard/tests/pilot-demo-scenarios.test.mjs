import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClientPath = join(root, "lib", "pilot-metrics-api.ts");
const pagePath = join(root, "app", "(dashboard)", "pilot-readiness", "demo-scenarios", "page.tsx");
const navPath = join(root, "components", "navigation.ts");
const readinessPagePath = join(root, "app", "(dashboard)", "pilot-readiness", "page.tsx");

const apiClient = readFileSync(apiClientPath, "utf8");
const page = readFileSync(pagePath, "utf8");
const nav = readFileSync(navPath, "utf8");
const readinessPage = readFileSync(readinessPagePath, "utf8");

test("API client exposes getPilotDemoScenarios targeting the guarded endpoint", () => {
  assert.match(apiClient, /getPilotDemoScenarios/);
  assert.match(apiClient, /\/api\/v1\/pilot\/demo-scenarios/);
  assert.match(apiClient, /ANALYTICS_READ/);
  assert.match(apiClient, /dashboardRequestHeaders\(pilotMetricsClient\.tenantId,\s*ANALYTICS_READ\)/);
  // Scenario contract fields are mapped; no raw payloads.
  for (const field of ["readiness", "requiredCapabilities", "evidenceSignals", "missingCapabilities", "safetyBoundaries", "operatorTalkingPoints"]) {
    assert.match(apiClient, new RegExp(field));
  }
  assert.doesNotMatch(apiClient, /predictionPayloadJson|beforePayloadJson|afterPayloadJson/);
});

test("demo scenarios page renders status, capabilities, evidence, gaps, and safety boundaries", () => {
  assert.equal(existsSync(pagePath), true);
  assert.match(page, /getPilotDemoScenarios/);
  assert.match(page, /Required capabilities/);
  assert.match(page, /Evidence signals/);
  assert.match(page, /Missing capabilities \/ gaps/);
  assert.match(page, /Safety boundaries/);
  assert.match(page, /Operator talking points/);
  assert.match(page, /No scenarios available/); // empty state
  // Cross-links back to readiness + evidence report.
  assert.match(page, /\/pilot-readiness\/evidence-report/);
  assert.match(page, /href="\/pilot-readiness"/);
  assert.doesNotMatch(page, /predictionPayloadJson|beforePayloadJson|afterPayloadJson/);
});

test("demo scenarios page surfaces the OP-CAP-11I scripted demo dataset and safety note", () => {
  assert.match(page, /Scripted demo dataset/);
  assert.match(page, /Demo data only/);
  assert.match(page, /PILOT_SCRIPTED_DEMO_DATASET\.md/);
  assert.match(page, /scripted-scenarios-demo\.json/);
  // Static, display-only dataset summary — no dangerous HTML injection.
  assert.doesNotMatch(page, /dangerouslySetInnerHTML/);
});

test("navigation and pilot-readiness link to demo scenarios", () => {
  assert.match(nav, /\/pilot-readiness\/demo-scenarios/);
  assert.match(readinessPage, /\/pilot-readiness\/demo-scenarios/);
});
