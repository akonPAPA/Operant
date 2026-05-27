import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "stage8-analytics-api.ts"), "utf8");
const inventoryPage = readFileSync(join(root, "app", "(dashboard)", "inventory", "page.tsx"), "utf8");
const reconciliationCasesPath = join(root, "components", "reconciliation-cases.tsx");
const reconciliationCases = readFileSync(reconciliationCasesPath, "utf8");

test("stage8 reconciliation API client exposes read model endpoints", () => {
  assert.match(apiClient, /\/api\/stage8\/reconciliation\/summary/);
  assert.match(apiClient, /\/api\/stage8\/reconciliation\/cases/);
  assert.match(apiClient, /\/api\/stage8\/reconciliation\/products\/\$\{productId\}\/timeline/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("inventory page renders reconciliation analytics and cases table", () => {
  assert.equal(existsSync(reconciliationCasesPath), true);
  assert.match(inventoryPage, /ReconciliationCases/);
  assert.match(reconciliationCases, /Inventory Analytics/);
  assert.match(reconciliationCases, /Inventory mismatch count/);
  assert.match(reconciliationCases, /High severity discrepancy count/);
  assert.match(reconciliationCases, /Stale inventory count/);
  assert.match(reconciliationCases, /Low stock count/);
  assert.match(reconciliationCases, /Reconciliation cases/);
  assert.match(reconciliationCases, /Product expected vs actual stock/);
  assert.match(reconciliationCases, /Likely causes/);
});

test("stage8 reconciliation UI remains detection-only", () => {
  assert.match(reconciliationCases, /does not mutate inventory, orders, quotes, connector state, or external systems/);
  assert.doesNotMatch(reconciliationCases, /reserveInventory|executeConnector|sendMessage|approve quote|finalize order/i);
});
