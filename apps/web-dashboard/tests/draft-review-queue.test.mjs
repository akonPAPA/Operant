import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "draft-review-api.ts"), "utf8");
const quoteQueue = readFileSync(join(root, "app", "(dashboard)", "workspace", "draft-quotes", "page.tsx"), "utf8");
const orderQueue = readFileSync(join(root, "app", "(dashboard)", "workspace", "draft-orders", "page.tsx"), "utf8");
const workspace = readFileSync(join(root, "components", "draft-review-workspace.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

const FORBIDDEN_CONTROLS =
  /Send to ERP|Approve final order|Approve final quote|Sync external|Create invoice|executeConnector|reserveInventory|finalizeOrder|sendToErp|createChangeRequest/i;
const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|rawMessage/i;

test("queue routes exist", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "draft-quotes", "page.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "draft-orders", "page.tsx")), true);
});

test("quote queue API helper calls the correct endpoint", () => {
  assert.match(apiClient, /getDraftQuoteReviewQueue/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-quotes\/review-queue/);
});

test("order queue API helper calls the correct endpoint", () => {
  assert.match(apiClient, /getDraftOrderReviewQueue/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-orders\/review-queue/);
});

test("product picker helper calls the correct product search endpoint", () => {
  assert.match(apiClient, /searchWorkspaceProducts/);
  assert.match(apiClient, /\/api\/v1\/workspace\/products\/search\?q=/);
});

test("quote queue page links to draft quote detail and has filter/table/empty states", () => {
  assert.match(quoteQueue, /getDraftQuoteReviewQueue/);
  assert.match(quoteQueue, /\/workspace\/draft-quotes\/\$\{summary\.draftId\}/);
  assert.match(quoteQueue, /name="status"/);
  assert.match(quoteQueue, /data-table/);
  assert.match(quoteQueue, /No draft quotes match/);
  assert.match(quoteQueue, /form-message error/);
  assert.match(quoteQueue, /DISABLED/);
  assert.doesNotMatch(quoteQueue, FORBIDDEN_CONTROLS);
});

test("order queue page links to draft order detail and has filter/table/empty states", () => {
  assert.match(orderQueue, /getDraftOrderReviewQueue/);
  assert.match(orderQueue, /\/workspace\/draft-orders\/\$\{summary\.draftId\}/);
  assert.match(orderQueue, /name="status"/);
  assert.match(orderQueue, /data-table/);
  assert.match(orderQueue, /No draft orders match/);
  assert.doesNotMatch(orderQueue, FORBIDDEN_CONTROLS);
});

test("navigation includes distinct draft review queue entries", () => {
  assert.match(navigation, /label: "Draft Quote Review"/);
  assert.match(navigation, /href: "\/workspace\/draft-quotes"/);
  assert.match(navigation, /label: "Draft Order Review"/);
  assert.match(navigation, /href: "\/workspace\/draft-orders"/);
});

test("detail component integrates the product picker", () => {
  assert.match(workspace, /searchWorkspaceProducts/);
  assert.match(workspace, /runProductSearch/);
  assert.match(workspace, /selectProduct/);
  assert.match(workspace, /Search products/);
  assert.match(workspace, /productResults/);
  assert.match(workspace, /form\.productId === item\.productId/);
});

test("queue UI and product picker do not reference raw AI/document/message payloads", () => {
  assert.doesNotMatch(apiClient, RAW_PAYLOAD);
  assert.doesNotMatch(quoteQueue, RAW_PAYLOAD);
  assert.doesNotMatch(orderQueue, RAW_PAYLOAD);
  assert.doesNotMatch(workspace, RAW_PAYLOAD);
});

test("product picker does not expose cost/margin/supplier fields in the typed item", () => {
  // ProductPickerItem type should only carry bounded display fields.
  assert.match(apiClient, /export type ProductPickerItem = \{[\s\S]*?\}/);
  const typeBlock = apiClient.match(/export type ProductPickerItem = \{[\s\S]*?\};/)?.[0] ?? "";
  assert.doesNotMatch(typeBlock, /cost|margin|supplier/i);
});

test("queue UI does not contain unsupported external/final-approval controls", () => {
  assert.doesNotMatch(quoteQueue, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(orderQueue, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(workspace, FORBIDDEN_CONTROLS);
});
