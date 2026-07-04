import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
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
  assert.match(api, /createDraftQuoteFromRfqHandoff/);
  assert.match(api, /decideRfqHandoffDraft/);
  assert.match(api, /RfqHandoffDraftQuoteLine/);
  assert.match(api, /RfqHandoffDraftQuoteIssue/);
  assert.match(api, /RfqHandoffDecisionResult/);
});

test("draft quote API contract mirrors backend line and issue fields", () => {
  const lineType = api.slice(
    api.indexOf("export type RfqHandoffDraftQuoteLine"),
    api.indexOf("export type RfqHandoffDraftQuoteIssue")
  );
  const issueType = api.slice(
    api.indexOf("export type RfqHandoffDraftQuoteIssue"),
    api.indexOf("export type RfqHandoffDraftQuote =")
  );
  assert.match(lineType, /rawSku: string \| null/);
  assert.match(lineType, /normalizedSku: string \| null/);
  assert.match(lineType, /quantity: number/);
  assert.match(lineType, /validationStatus: string/);
  assert.match(lineType, /issueCodes: string/);
  assert.match(issueType, /draftQuoteLineId: string \| null/);
  assert.match(issueType, /issueCode: string/);
  assert.match(issueType, /blocking: boolean/);
  assert.doesNotMatch(issueType, /\bcode\??:/);
});

test("api client targets the OP-CAP-06B/06C channel routes", () => {
  assert.match(api, /\/api\/v1\/channels\/rfq-handoffs/);
  assert.match(api, /start-review/);
  assert.match(api, /dismiss/);
  assert.match(api, /mark-converted/);
});

test("api client separates channel read and mutation permissions", () => {
  assert.match(api, /X-Tenant-Id/);
  assert.match(api, /X-OrderPilot-Permissions/);
  assert.match(api, /CHANNELS_READ_PERMISSION = "ADMIN_SETTINGS_READ"/);
  assert.match(api, /CHANNELS_MANAGE_PERMISSION = "ADMIN_SETTINGS_MANAGE"/);
  for (const functionName of [
    "startReviewRfqHandoff",
    "dismissRfqHandoff",
    "markConvertedRfqHandoff"
  ]) {
    const start = api.indexOf(`export function ${functionName}`);
    const end = api.indexOf("\n}\n", start);
    const action = api.slice(start, end);
    assert.match(
      action,
      /"X-OrderPilot-Permissions": CHANNELS_MANAGE_PERMISSION/
    );
  }
});

test("api client transition payloads do not send backend-owned actor fields", () => {
  assert.doesNotMatch(api, /reviewerUserId:\s*reviewerUserId/);
  assert.doesNotMatch(api, /actorUserId:\s*actorUserId/);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*actorUserId/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*reviewerUserId/s);
});

test("api client contextual AI payload does not send source ids or context text", () => {
  assert.match(api, /\/api\/v1\/ai-work\/rfq-handoffs\/\$\{id\}\/suggestions/);
  assert.match(api, /"Idempotency-Key": idempotencyKey/);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*idempotencyKey/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*sourceId/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*sourceType/s);
  assert.doesNotMatch(api, /JSON\.stringify\(\{[^}]*contextText/s);
});

test("workspace renders safe AI suggestion fields, candidates, risk, and confidence", () => {
  assert.match(workspace, /aiSuggestion\.summary/);
  assert.match(workspace, /aiSuggestion\.nextActionCandidates/);
  assert.match(workspace, /aiSuggestion\.displayFields/);
  assert.match(workspace, /aiSuggestion\.riskFlags/);
  assert.match(workspace, /aiSuggestion\.riskLevel/);
  assert.match(workspace, /aiSuggestion\.confidence/);
  assert.match(workspace, /Advisory only/);
  assert.doesNotMatch(workspace, /aiSuggestion\.generatedText/);
  assert.doesNotMatch(workspace, /structuredPayloadJson|evidenceRefsJson/);
});

test("api client has no manual handoff create or ERP action", () => {
  assert.doesNotMatch(api, /createRfqHandoff|createHandoff/);
  assert.doesNotMatch(api, /createOrder|approveQuote|approveOrder|erpSync|updateInventory|priceUpdate/i);
});

test("api client type exposes no secret or raw payload fields", () => {
  assert.doesNotMatch(api, /secretRef|secretReference|botToken|webhookSecret/i);
  assert.doesNotMatch(api, /rawPayloadJson|rawPayload\b/i);
});

test("RfqHandoff response type declares no internal actor or raw source/correlation ids", () => {
  // Category D: the operator-safe response contract must not reintroduce these fields.
  const typeBlock = api.slice(api.indexOf("export type RfqHandoff ="), api.indexOf("export type RfqHandoffApiResult"));
  assert.doesNotMatch(typeBlock, /reviewerUserId/);
  assert.doesNotMatch(typeBlock, /inboundChannelEventId/);
  assert.doesNotMatch(typeBlock, /channelConnectionId/);
  assert.doesNotMatch(typeBlock, /sourceExternalEventId/);
  // Still exposes the business fields the operator screen needs.
  assert.match(typeBlock, /sourceChannel/);
  assert.match(typeBlock, /sourceActorExternalId/);
  assert.match(typeBlock, /requestPreview/);
  assert.match(typeBlock, /status/);
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
  assert.match(workspace, /Close without draft/);
  assert.match(workspace, /Generate suggestion/);
  assert.match(workspace, /Create draft quote/);
  assert.match(workspace, /Send deterministic demo RFQ/);
});

test("workspace requires a dismiss reason before enabling confirm", () => {
  assert.match(workspace, /dismissReason/);
  assert.match(workspace, /!dismissReason\.trim\(\)/);
});

test("manual close is secondary, explicit, and requires a non-blank note", () => {
  assert.match(workspace, /Close without draft/);
  assert.match(workspace, /closes the handoff without creating a draft quote/);
  assert.match(workspace, /!conversionNote\.trim\(\)/);
  assert.match(workspace, /button secondary-button/);
  assert.doesNotMatch(workspace, />Mark converted/);
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

test("workspace wires the existing transitions and the safe reviewed-handoff draft action", () => {
  assert.match(workspace, /submitStartReview/);
  assert.match(workspace, /submitDismiss/);
  assert.match(workspace, /submitMarkConverted/);
  assert.match(workspace, /submitCreateDraftQuote/);
  assert.match(workspace, /canCreateDraftQuote/);
  assert.match(workspace, /status === "IN_REVIEW"/);
  assert.match(workspace, /draftResult\.draftQuote\.status/);
  assert.match(workspace, /draftResult\.auditStatus/);
  assert.match(workspace, /draftResult\.outboxStatus/);
  assert.match(workspace, /draftResult\.externalWriteSafety/);
  assert.match(workspace, /draftResult\.draftQuote\.lines/);
  assert.match(workspace, /draftResult\.draftQuote\.issues/);
  assert.match(workspace, /Validation issues/);
  assert.match(workspace, /line\.rawSku/);
  assert.match(workspace, /line\.normalizedSku/);
  assert.match(workspace, /line\.quantity/);
  assert.match(workspace, /line\.uom/);
  assert.match(workspace, /line\.validationStatus/);
  assert.match(workspace, /formatIssueCodes\(line\.issueCodes\)/);
  assert.match(workspace, /issue\.issueCode/);
  assert.doesNotMatch(
    workspace,
    /(createOrder|approveQuote|approveOrder|syncErp|erpSync|updateInventory|updatePrice|updateCustomer)\s*\(/i
  );
});

test("draft quote action sends only the safe route handle and an empty intent body", () => {
  assert.match(api, /\/api\/v1\/quotes\/drafts\/from-rfq-handoff\/\$\{id\}/);
  assert.match(api, /"X-OrderPilot-Permissions": QUOTE_ACTION/);
  const action = api.slice(
    api.indexOf("export function createDraftQuoteFromRfqHandoff"),
    api.indexOf("export function decideRfqHandoffDraft")
  );
  assert.match(action, /body:\s*JSON\.stringify\(\{\}\)/);
  assert.doesNotMatch(
    action,
    /tenantId|actorId|userId|createdBy|approvedBy|decidedBy|status|approvalStatus|executionStatus|riskLevel|margin|stock|rawPayload|idempotencyKey/
  );
});

test("operator decision submits business intent and idempotency header only", () => {
  assert.match(
    api,
    /\/api\/v1\/quotes\/drafts\/from-rfq-handoff\/\$\{id\}\/decision/
  );
  const action = api.slice(
    api.indexOf("export function decideRfqHandoffDraft"),
    api.indexOf("// --- Display helpers ---")
  );
  assert.match(action, /"Idempotency-Key": idempotencyKey/);
  assert.match(action, /body:\s*JSON\.stringify\(\{ decision, note \}\)/);
  assert.doesNotMatch(
    action,
    /JSON\.stringify\(\{[^}]*tenantId|JSON\.stringify\(\{[^}]*actorId|JSON\.stringify\(\{[^}]*status|JSON\.stringify\(\{[^}]*approval|JSON\.stringify\(\{[^}]*execution|JSON\.stringify\(\{[^}]*risk|JSON\.stringify\(\{[^}]*margin|JSON\.stringify\(\{[^}]*stock/
  );
  assert.doesNotMatch(action, /X-OrderPilot-Actor-Id/);
  assert.doesNotMatch(action, /["']actorId["']\s*:/);
});

test("local demo actor authority remains backend-owned", () => {
  assert.doesNotMatch(api, /X-OrderPilot-Actor-Id/);
  assert.doesNotMatch(api, /NEXT_PUBLIC_.*ACTOR/);
  assert.match(api, /"X-OrderPilot-Permissions": QUOTE_ACTION/);
});

test("workspace displays terminal decision, audit, and disabled external execution state", () => {
  assert.match(workspace, /submitDraftDecision/);
  assert.match(workspace, /COMPLETE_DEMO/);
  assert.match(workspace, /DECLINE_DEMO/);
  assert.match(workspace, /decisionResult\.decision/);
  assert.match(workspace, /decisionResult\.quoteState/);
  assert.match(workspace, /decisionResult\.terminalState/);
  assert.match(workspace, /decisionResult\.auditStatus/);
  assert.match(workspace, /decisionResult\.safetySummary/);
  assert.match(workspace, /decisionResult\.externalExecution/);
  assert.match(workspace, /decisionResult\.connectorAction/);
  assert.match(workspace, /externalExecution = DISABLED/);
  assert.match(workspace, /connector call = NOT_INVOKED/);
  assert.doesNotMatch(
    workspace,
    /decisionResult\.(tenantId|actorId|idempotencyKey|auditEventId|rawAiPayload|connectorCredentials)/
  );
});

test("RFQ client maps failures to safe messages without exposing raw backend bodies", () => {
  assert.match(api, /rfqHandoffStatusMessage/);
  assert.doesNotMatch(api, /"message"\s+in\s+data/);
  assert.doesNotMatch(api, /error instanceof Error \? error\.message/);
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
