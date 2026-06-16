import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const quoteRoute = readFileSync(join(root, "app", "(dashboard)", "workspace", "draft-quotes", "[id]", "page.tsx"), "utf8");
const orderRoute = readFileSync(join(root, "app", "(dashboard)", "workspace", "draft-orders", "[id]", "page.tsx"), "utf8");
const workspace = readFileSync(join(root, "components", "draft-review-workspace.tsx"), "utf8");
const apiClient = readFileSync(join(root, "lib", "draft-review-api.ts"), "utf8");

// Forbidden external-execution / final-approval control identifiers (camelCase or explicit button text).
const FORBIDDEN_CONTROLS =
  /Send to ERP|Approve final order|Approve final quote|Sync external|Create invoice|executeConnector|reserveInventory|finalizeOrder|sendToErp|prepareDraftQuote|prepareDraftOrder|createChangeRequest/i;
// Forbidden raw payload field references.
const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|rawMessage/i;

test("draft review routes exist", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "draft-quotes", "[id]", "page.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "draft-orders", "[id]", "page.tsx")), true);
});

test("API helper builds the correct draft quote review endpoint", () => {
  assert.match(apiClient, /getDraftQuoteReview/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-quotes\/\$\{draftQuoteId\}\/review/);
  assert.match(apiClient, /updateDraftQuoteLine/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-quotes\/\$\{draftQuoteId\}\/lines\/\$\{lineId\}/);
  assert.match(apiClient, /method: "PATCH"/);
  assert.match(apiClient, /markDraftQuoteReady/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-quotes\/\$\{draftQuoteId\}\/mark-ready/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("API helper builds the correct draft order review endpoint", () => {
  assert.match(apiClient, /getDraftOrderReview/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-orders\/\$\{draftOrderId\}\/review/);
  assert.match(apiClient, /updateDraftOrderLine/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-orders\/\$\{draftOrderId\}\/lines\/\$\{lineId\}/);
  assert.match(apiClient, /markDraftOrderReady/);
  assert.match(apiClient, /\/api\/v1\/workspace\/draft-orders\/\$\{draftOrderId\}\/mark-ready/);
});

test("quote review page uses bounded review endpoint, line table, correction and mark-ready", () => {
  assert.match(quoteRoute, /getDraftQuoteReview/);
  assert.match(quoteRoute, /DraftReviewWorkspace/);
  assert.match(quoteRoute, /draftType="QUOTE"/);
  assert.match(workspace, /Draft lines/);
  assert.match(workspace, /data-table/);
  assert.match(workspace, /updateDraftQuoteLine/);
  assert.match(workspace, /Save correction/);
  assert.match(workspace, /markDraftQuoteReady/);
  assert.match(workspace, /Mark ready for internal approval/);
  assert.doesNotMatch(quoteRoute, FORBIDDEN_CONTROLS);
});

test("order review page uses bounded review endpoint, line table, correction and mark-ready", () => {
  assert.match(orderRoute, /getDraftOrderReview/);
  assert.match(orderRoute, /DraftReviewWorkspace/);
  assert.match(orderRoute, /draftType="ORDER"/);
  assert.match(workspace, /updateDraftOrderLine/);
  assert.match(workspace, /markDraftOrderReady/);
  assert.doesNotMatch(orderRoute, FORBIDDEN_CONTROLS);
});

test("workspace exposes no external-execution or final-approval controls", () => {
  assert.doesNotMatch(workspace, FORBIDDEN_CONTROLS);
  assert.match(workspace, /Internal draft review only/);
  assert.match(workspace, /External execution/);
});

test("client-side validation rejects non-positive quantity", () => {
  assert.match(workspace, /Quantity must be a positive number/);
  assert.match(workspace, /qty <= 0/);
  assert.match(workspace, /price < 0/);
  assert.match(workspace, /MAX_UOM_LENGTH/);
  assert.match(workspace, /validateForm/);
});

test("mark-ready respects backend status model", () => {
  assert.match(workspace, /WAITING_APPROVAL/);
  assert.match(workspace, /LOCKED_STATUSES/);
  assert.match(workspace, /Already marked ready for internal approval/);
  assert.match(workspace, /locked status/);
});

test("loading, empty and error states are handled", () => {
  assert.match(workspace, /No lines on this draft yet/);
  assert.match(workspace, /Saving correction/);
  assert.match(workspace, /Marking ready/);
  assert.match(workspace, /form-message error/);
  assert.match(workspace, /unavailable/);
});

test("no raw AI/document/message payload fields are referenced", () => {
  assert.doesNotMatch(workspace, RAW_PAYLOAD);
  assert.doesNotMatch(apiClient, RAW_PAYLOAD);
  assert.doesNotMatch(quoteRoute, RAW_PAYLOAD);
  assert.doesNotMatch(orderRoute, RAW_PAYLOAD);
});
