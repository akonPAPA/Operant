import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClientPath = join(root, "lib", "order-journey-api.ts");
const listPath = join(root, "components", "order-journey-list.tsx");
const detailPath = join(root, "components", "order-journey-detail.tsx");
const timelinePath = join(root, "components", "order-journey-timeline.tsx");
const badgePath = join(root, "components", "order-journey-status-badge.tsx");
const signalPath = join(root, "components", "fulfillment-signal-panel.tsx");
const listPage = join(root, "app", "(dashboard)", "order-journey", "page.tsx");
const detailPage = join(root, "app", "(dashboard)", "order-journey", "[id]", "page.tsx");
const navPath = join(root, "components", "navigation-registry.ts");
const brandPath = join(root, "lib", "brand.ts");

const apiClient = readFileSync(apiClientPath, "utf8");
const list = readFileSync(listPath, "utf8");
const detail = readFileSync(detailPath, "utf8");
const timeline = readFileSync(timelinePath, "utf8");
const badge = readFileSync(badgePath, "utf8");
const signal = readFileSync(signalPath, "utf8");
const nav = readFileSync(navPath, "utf8");

test("order journey API client is tenant + permission scoped; reads stay GET-only", () => {
  assert.equal(existsSync(apiClientPath), true);
  assert.match(apiClient, /\/api\/v1\/order-journeys/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /X-OrderPilot-Permissions/);
  assert.match(apiClient, /ANALYTICS_READ/);
  // The shared read helper uses GET and ANALYTICS_READ. OP-CAP-46D added a single,
  // explicitly REVIEW_ACTION-guarded mutation (createOrderJourneyTrackingLink); no
  // other writes are allowed in this client.
  const readBlock = apiClient.slice(0, apiClient.indexOf("createOrderJourneyTrackingLink"));
  assert.doesNotMatch(readBlock, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
  const postCount = (apiClient.match(/method:\s*"POST"/g) ?? []).length;
  assert.equal(postCount, 1, "exactly one POST mutation is allowed (tracking link)");
  assert.doesNotMatch(apiClient, /method:\s*"(PUT|PATCH|DELETE)"/);
});

test("navigation includes Order Journey under the Transactions group", () => {
  const txIndex = nav.indexOf('section: "Transactions"');
  const catalogIndex = nav.indexOf('section: "Catalog"');
  const journeyIndex = nav.indexOf('path: "/order-journey"');
  assert.ok(txIndex >= 0 && journeyIndex > txIndex && journeyIndex < catalogIndex,
    "Order Journey link must sit inside the Transactions group");
});

test("list renders the required columns and honest empty state", () => {
  assert.match(list, /Current stage/);
  assert.match(list, /Evidence/);
  assert.match(list, /Blocked/);
  assert.match(list, /Last signal/);
  assert.match(list, /No order journey signals yet/);
});

test("badges distinguish verified, mirrored, estimated, unknown and blocked", () => {
  assert.match(badge, /VERIFIED/);
  assert.match(badge, /MIRRORED/);
  assert.match(badge, /ESTIMATED/);
  assert.match(badge, /UNKNOWN/);
  assert.match(badge, /Blocked/);
});

test("timeline shows state + evidence + customer-visible vs internal-only", () => {
  assert.match(timeline, /MilestoneStateBadge/);
  assert.match(timeline, /EvidenceBadge/);
  assert.match(timeline, /Customer-visible/);
  assert.match(timeline, /Internal only/);
});

test("detail separates internal vs customer-visible status and honest payment state", () => {
  assert.match(detail, /Internal status/);
  assert.match(detail, /Customer-visible status/);
  assert.match(detail, /Payment status unavailable/);
  assert.match(detail, /Customer-safe status preview/);
});

test("no fake carrier / GPS / live tracking / payment confirmation copy", () => {
  for (const src of [list, detail, timeline, signal]) {
    assert.doesNotMatch(src, /GPS|live tracking|map view|tracking number|carrier api|payment confirmed|paid in full/i);
  }
  assert.match(signal, /not connected yet/i);
});

test("no mutation or external-write controls in read surfaces", () => {
  for (const src of [list, detail, timeline, signal]) {
    assert.doesNotMatch(src, /<form|onSubmit|executeConnector|approve order|sendMessage/i);
  }
});

test("OP-CAP-23: api client exposes read-only projector health and projection source type", () => {
  assert.match(apiClient, /\/api\/v1\/order-journeys\/projection-health/);
  assert.match(apiClient, /getJourneyProjectionHealth/);
  assert.match(apiClient, /projectionSource\?/);
  // The health read stays a GET (no projector mutation control wired into the read client).
  // OP-CAP-46D added one POST for tracking-link minting; assert the projector-health helper
  // stays a GET regardless.
  assert.match(apiClient, /read<JourneyProjectionHealth>\("\/api\/v1\/order-journeys\/projection-health"\)/);
});

test("OP-CAP-23: detail honestly reports projection source without inventing status", () => {
  assert.match(detail, /Prepared by projector/);
  assert.match(detail, /Refreshed on read \(projector pending\)/);
  assert.match(detail, /ON_READ_FALLBACK/);
  // still no fake projector control buttons in the read surface
  assert.doesNotMatch(detail, /Run projector|Process events|Force refresh/i);
});

test("pages exist and keep the Operant shell; branding stays Operant not OrderPilot", () => {
  assert.equal(existsSync(listPage), true);
  assert.equal(existsSync(detailPage), true);
  assert.match(readFileSync(listPage, "utf8"), /DashboardShell/);
  const brand = readFileSync(brandPath, "utf8");
  assert.match(brand, /Operant/);
  assert.doesNotMatch(brand, /OrderPilot/);
  // Technical API header is intentionally retained.
  assert.match(apiClient, /X-OrderPilot-Permissions/);
});
