import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const demoPage = readFileSync(join(root, "components", "demo-dashboard.tsx"), "utf8");
const demoRoute = readFileSync(join(root, "app", "(dashboard)", "demo", "page.tsx"), "utf8");
const apiClient = readFileSync(join(root, "lib", "demo-api.ts"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");
const quoteWorkspace = readFileSync(join(root, "components", "quote-workspace.tsx"), "utf8");
const quoteReviewCockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");

test("demo page route renders the investor demo dashboard", () => {
  assert.match(demoRoute, /DashboardShell title="Investor Demo"/);
  assert.match(demoRoute, /<DemoDashboard \/>/);
});

test("demo page renders KPI cards and Telegram RFQ panel", () => {
  assert.match(demoPage, /Investor demo KPIs/);
  assert.match(demoPage, /Bot RFQ requests/);
  assert.match(demoPage, /Human handoffs/);
  assert.match(demoPage, /Telegram RFQ panel/);
  assert.match(apiClient, /Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty\./);
  assert.doesNotMatch(apiClient, /Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty\./);
  assert.doesNotMatch(demoPage, /20 pcs/);
});

test("Stage 13D investor demo keeps frozen RFQ payload and seeded defaults", () => {
  assert.match(apiClient, /const DEMO_RFQ_TEXT = "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty\."/);
  assert.match(apiClient, /demoTelegramRfqText = DEMO_RFQ_TEXT/);
  assert.match(demoPage, /externalExecution=DISABLED remains visible/);
  assert.match(demoPage, /External execution<\/dt><dd>DISABLED/);

  assert.match(quoteWorkspace, /defaultValue="CUST-001"/);
  assert.match(quoteWorkspace, /defaultValue="PAD-OE-04465"/);
  assert.match(quoteWorkspace, /defaultValue="WH-ALM"/);
  assert.match(quoteWorkspace, /defaultValue="2"/);
  assert.match(quoteWorkspace, /defaultValue="EA"/);
  assert.match(quoteWorkspace, /keeps externalExecution=DISABLED/);
});

test("Stage 13E final preflight keeps frozen demo safe and reproducible", () => {
  assert.match(apiClient, /Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty\./);
  assert.doesNotMatch(apiClient, /demoTelegramRfqPayload|update_id: 91001|message_id: 7001/);
  assert.match(demoPage, /externalExecution=DISABLED/);
  assert.match(quoteReviewCockpit, /externalExecution=DISABLED/);
  assert.match(quoteWorkspace, /External ERP write: disabled \/ not executed/);
  assert.match(quoteWorkspace, /External ERP write was not executed/);

  assert.match(quoteWorkspace, /customerExternalRef: String\(form\.get\("customerExternalRef"\) \|\| "CUST-001"\)/);
  assert.match(quoteWorkspace, /rawSkuOrAlias: String\(form\.get\("rawSkuOrAlias"\) \|\| "PAD-OE-04465"\)/);
  assert.match(quoteWorkspace, /requestedLocation: String\(form\.get\("requestedLocation"\) \|\| "WH-ALM"\)/);
  assert.match(quoteWorkspace, /quantity: Number\(form\.get\("quantity"\) \|\| 2\)/);
  assert.match(quoteWorkspace, /uom: String\(form\.get\("uom"\) \|\| "EA"\)/);

  const combinedDemoUi = `${demoPage}\n${quoteWorkspace}\n${quoteReviewCockpit}`;
  assert.doesNotMatch(combinedDemoUi, /autonomous (?:ERP|1C|connector|external)/i);
  assert.doesNotMatch(combinedDemoUi, /automatically (?:executes|writes|posts|sends) (?:to )?(?:ERP|1C|connector)/i);
  assert.doesNotMatch(combinedDemoUi, /external execution enabled/i);
  assert.doesNotMatch(combinedDemoUi, /ERP write enabled/i);
  assert.doesNotMatch(combinedDemoUi, /connector write enabled/i);
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
  assert.match(apiClient, /X-OrderPilot-Permissions": "ANALYTICS_READ"/);
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
