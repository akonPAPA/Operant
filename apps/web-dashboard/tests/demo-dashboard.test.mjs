import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const demoPage = readFileSync(join(root, "components", "demo-dashboard.tsx"), "utf8");
const demoRoute = readFileSync(join(root, "app", "(dashboard)", "demo", "page.tsx"), "utf8");
const apiClient = readFileSync(join(root, "lib", "demo-api.ts"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

test("demo page route renders the investor demo dashboard", () => {
  assert.match(demoRoute, /DashboardShell title="Investor Demo"/);
  assert.match(demoRoute, /<DemoDashboard \/>/);
});

test("demo page renders KPI cards and Telegram RFQ panel", () => {
  assert.match(demoPage, /Investor demo KPIs/);
  assert.match(demoPage, /Bot RFQ requests/);
  assert.match(demoPage, /Human handoffs/);
  assert.match(demoPage, /Telegram RFQ panel/);
  assert.match(apiClient, /Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty\./);
});

test("demo page renders canonical reconciliation mismatch values", () => {
  assert.match(apiClient, /openingStock: 150/);
  assert.match(apiClient, /sold: 34/);
  assert.match(apiClient, /expectedStock: 116/);
  assert.match(apiClient, /actualStock: 100/);
  assert.match(apiClient, /mismatch: -16/);
  assert.match(apiClient, /severity: "HIGH"/);
});

test("demo page renders security and trust controls", () => {
  assert.match(demoPage, /Security and trust panel/);
  assert.match(demoPage, /AI\/bot cannot approve a quote/);
  assert.match(demoPage, /Bot cannot create a final order/);
  assert.match(demoPage, /No ERP write exists/);
  assert.match(demoPage, /Tenant isolation uses the X-Tenant-Id boundary/);
});

test("API client has configurable base URL, tenant header, and graceful failure contract", () => {
  assert.match(apiClient, /NEXT_PUBLIC_CORE_API_URL/);
  assert.match(apiClient, /http:\/\/localhost:8080/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /Backend returned \$\{response\.status\}/);
  assert.match(apiClient, /Core API is not reachable from the browser/);
  assert.match(apiClient, /Demo backend data not seeded yet/);
});

test("key investor demo navigation routes have page files", () => {
  const routes = [
    ["Investor Demo", "/demo", join(root, "app", "(dashboard)", "demo", "page.tsx")],
    ["Command Center", "/command-center", join(root, "app", "(dashboard)", "command-center", "page.tsx")],
    ["Inbox", "/inbox", join(root, "app", "(dashboard)", "inbox", "page.tsx")],
    ["Bot / Conversations", "/bot-conversations", join(root, "app", "(dashboard)", "bot-conversations", "page.tsx")],
    ["Reconciliation", "/reconciliation", join(root, "app", "(dashboard)", "reconciliation", "page.tsx")],
    ["Analytics", "/analytics", join(root, "app", "(dashboard)", "analytics", "page.tsx")],
    ["Audit / Security", "/audit-log", join(root, "app", "(dashboard)", "audit-log", "page.tsx")],
    ["Integrations", "/integrations", join(root, "app", "(dashboard)", "integrations", "page.tsx")]
  ];

  for (const [label, href, path] of routes) {
    assert.match(navigation, new RegExp(`label: "${label.replace("/", "\\/")}"`));
    assert.match(navigation, new RegExp(`href: "${href.replace("/", "\\/")}"`));
    assert.equal(existsSync(path), true, `${href} page file should exist`);
  }

  assert.equal(existsSync(join(root, "app", "(dashboard)", "bot", "conversations", "page.tsx")), true);
});
