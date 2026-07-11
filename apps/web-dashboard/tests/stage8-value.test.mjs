import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const valueApiPath = join(root, "lib", "stage8-value-api.ts");
const valueApi = readFileSync(valueApiPath, "utf8");
const commandCenter = readFileSync(join(root, "app", "(dashboard)", "command-center", "page.tsx"), "utf8");
const businessValuePath = join(root, "components", "business-value-analytics.tsx");
const businessValue = readFileSync(businessValuePath, "utf8");
const assumptionsPanel = readFileSync(join(root, "components", "roi-assumptions-panel.tsx"), "utf8");

test("stage8 value API client exposes tenant-scoped ROI endpoints", () => {
  assert.equal(existsSync(valueApiPath), true);
  assert.match(valueApi, /\/api\/stage8\/value\/summary/);
  assert.match(valueApi, /\/api\/stage8\/value\/roi-assumptions/);
  assert.match(valueApi, /\/api\/stage8\/value\/export/);
  assert.match(valueApi, /dashboardRequestHeaders\(stage8ValueConfig\.tenantId\)/);
});

test("command center renders Business Value cards and estimated labels", () => {
  assert.equal(existsSync(businessValuePath), true);
  assert.match(commandCenter, /BusinessValueAnalytics/);
  assert.match(businessValue, /Business Value/);
  assert.match(businessValue, /Estimated hours saved/);
  assert.match(businessValue, /Estimated labor cost saved/);
  assert.match(businessValue, /Unsafe attempts blocked/);
  assert.match(businessValue, /Discount leakage/);
  assert.match(businessValue, /Margin risk/);
  assert.match(businessValue, /Recovered revenue via substitutes/);
  assert.match(businessValue, /Inventory discrepancy value/);
  assert.match(businessValue, /Estimated values are pilot ROI indicators, not booked revenue/);
});

test("ROI assumptions panel distinguishes defaults from tenant assumptions", () => {
  assert.match(assumptionsPanel, /ROI assumptions/);
  assert.match(assumptionsPanel, /Manual handling time/);
  assert.match(assumptionsPanel, /Operator hourly cost/);
  assert.match(assumptionsPanel, /Attribution mode/);
  assert.match(assumptionsPanel, /safe demo defaults/);
  assert.match(assumptionsPanel, /tenant-specific ROI assumptions/);
});

test("stage8 value UI does not expose unsafe business controls", () => {
  assert.doesNotMatch(businessValue, /sendMessage|approve quote|finalize order|executeConnector|reserveInventory/i);
  assert.doesNotMatch(assumptionsPanel, /sendMessage|approve quote|finalize order|executeConnector|reserveInventory/i);
});
