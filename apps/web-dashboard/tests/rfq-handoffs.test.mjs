import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const api = readFileSync(join(root, "lib", "rfq-handoff-api.ts"), "utf8");
const workspace = readFileSync(join(root, "components", "rfq-handoff-workspace.tsx"), "utf8");
const page = readFileSync(join(root, "app", "(dashboard)", "channels", "rfq-handoffs", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

// --- API client ---

test("rfq handoff api client declares the workflow types", () => {
  assert.match(api, /RfqHandoffStatus/);
  assert.match(api, /type RfqHandoff =/);
  assert.match(api, /PENDING_REVIEW/);
  assert.match(api, /IN_REVIEW/);
  assert.match(api, /CONVERTED/);
  assert.match(api, /DISMISSED/);
});

test("api client exposes read and the three transition functions", () => {
  assert.match(api, /listRfqHandoffs/);
  assert.match(api, /getRfqHandoff/);
  assert.match(api, /startReviewRfqHandoff/);
  assert.match(api, /dismissRfqHandoff/);
  assert.match(api, /markConvertedRfqHandoff/);
  assert.match(api, /generateRfqHandoffAiSuggestion/);
});

test("api client targets the OP-CAP-06B/06C channel routes", () => {
  assert.match(api, /\/api\/v1\/channels\/rfq-handoffs/);
  assert.match(api, /start-review/);
  assert.match(api, /dismiss/);
  assert.match(api, /mark-converted/);
});

test("api client sends tenant and ADMIN_SETTINGS_READ permission headers", () => {
  assert.match(api, /X-Tenant-Id/);
  assert.match(api, /X-OrderPilot-Permissions/);
  assert.match(api, /ADMIN_SETTINGS_READ/);
});

test("api client transition payloads do not send backend-owned actor fields", () => {
  assert.doesNotMatch(api, /reviewerUserId:\s*reviewerUserId/);
  assert.doesNotMatch(api, /actorUserId:\s*actorUserId/);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*actorUserId/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*reviewerUserId/s);
});

test("api client contextual AI payload does not send source ids or context text", () => {
  assert.match(api, /\/api\/v1\/ai-work\/rfq-handoffs\/\$\{id\}\/suggestions/);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*sourceId/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*sourceType/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*contextText/s);
});

test("api client has no manual create function and no quote/order/erp action", () => {
  assert.doesNotMatch(api, /createRfqHandoff|createHandoff/);
  assert.doesNotMatch(api, /createQuote|createOrder|approve|erpSync|inventory|priceUpdate/i);
});

test("api client type exposes no secret or raw payload fields", () => {
  assert.doesNotMatch(api, /secretRef|secretReference|botToken|webhookSecret/i);
  assert.doesNotMatch(api, /rawPayloadJson|rawPayload\b/i);
});

test("api client status helpers cover all four statuses", () => {
  assert.match(api, /statusLabel/);
  assert.match(api, /statusClass/);
  assert.match(api, /isTerminal/);
});

// --- Workspace component ---

test("workspace is a client component with status filter and actions", () => {
  assert.match(workspace, /"use client"/);
  assert.match(workspace, /STATUS_FILTERS/);
  assert.match(workspace, /Start review/);
  assert.match(workspace, /Dismiss/);
  assert.match(workspace, /Mark converted/);
  assert.match(workspace, /Generate suggestion/);
});

test("workspace requires a dismiss reason before enabling confirm", () => {
  assert.match(workspace, /dismissReason/);
  assert.match(workspace, /!dismissReason\.trim\(\)/);
});

test("workspace gates start review to PENDING_REVIEW and blocks terminal transitions", () => {
  assert.match(workspace, /canStartReview/);
  assert.match(workspace, /status === "PENDING_REVIEW"/);
  assert.match(workspace, /isTerminal/);
});

test("workspace exposes loading, empty, and error states", () => {
  assert.match(workspace, /isLoadingDetail/);
  assert.match(workspace, /isLoadingList/);
  assert.match(workspace, /No RFQ handoffs for this filter/);
  assert.match(workspace, /form-message/);
});

test("workspace detail does not render low-level source or internal actor identifiers", () => {
  assert.doesNotMatch(workspace, /Source event ID/);
  assert.doesNotMatch(workspace, /detail\.sourceExternalEventId/);
  assert.doesNotMatch(workspace, /detail\.reviewerUserId/);
});

test("workspace wires only the three safe transitions, no quote/order/ERP action calls", () => {
  // Only the three controlled transition submitters exist.
  assert.match(workspace, /submitStartReview/);
  assert.match(workspace, /submitDismiss/);
  assert.match(workspace, /submitMarkConverted/);
  // No dangerous mutation is invoked (function-call form, so safety-copy prose is not matched).
  assert.doesNotMatch(
    workspace,
    /(createQuote|createOrder|approveQuote|approveOrder|syncErp|erpSync|updateInventory|updatePrice|updateCustomer)\s*\(/i
  );
});

// --- Page + navigation ---

test("page is a server loader that pre-fetches pending handoffs", () => {
  assert.match(page, /listRfqHandoffs\("PENDING_REVIEW"\)/);
  assert.match(page, /RfqHandoffWorkspace/);
  assert.match(page, /DashboardShell/);
});

test("navigation links to the RFQ handoffs surface", () => {
  assert.match(navigation, /RFQ Handoffs/);
  assert.match(navigation, /\/channels\/rfq-handoffs/);
});
