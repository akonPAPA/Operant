import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClientPath = join(root, "lib", "stage8-analytics-api.ts");
const componentPath = join(root, "components", "command-center-analytics.tsx");
const commandCenterRoute = readFileSync(join(root, "app", "(dashboard)", "command-center", "page.tsx"), "utf8");
const apiClient = readFileSync(apiClientPath, "utf8");
const component = readFileSync(componentPath, "utf8");

test("stage8 analytics API client targets command center endpoint with tenant boundary", () => {
  assert.equal(existsSync(apiClientPath), true);
  assert.match(apiClient, /\/api\/stage8\/analytics\/command-center/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /CORE_API_BASE_URL/);
});

test("command center renders Stage 8A commerce intelligence cards", () => {
  assert.equal(existsSync(componentPath), true);
  assert.match(commandCenterRoute, /CommandCenterAnalytics/);
  assert.match(component, /Commerce Intelligence/);
  assert.match(component, /Total inbound requests/);
  assert.match(component, /Bot handoffs/);
  assert.match(component, /Validation-backed reviews/);
  assert.match(component, /Blocked unsafe draft attempts/);
  assert.match(component, /Exception rate/);
  assert.match(component, /Drafts prepared/);
  assert.match(component, /Channel mix/);
});

test("stage8 command center copy preserves bot review boundary", () => {
  assert.match(component, /Bot handoffs remain separate from validation-backed reviews/);
  assert.doesNotMatch(component, /sendMessage|approve quote|finalize order|executeConnector/i);
});
