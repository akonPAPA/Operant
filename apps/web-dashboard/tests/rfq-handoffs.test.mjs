import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const api = readFileSync(join(root, "lib", "rfq-handoff-api.ts"), "utf8");
const aiWorkApi = readFileSync(join(root, "lib", "ai-work-api.ts"), "utf8");
const workspace = readFileSync(join(root, "components", "rfq-handoff-workspace.tsx"), "utf8");
const aiWorkSchemaView = readFileSync(
  join(root, "components", "ai-work-schema-v1-view.tsx"),
  "utf8"
);
const page = readFileSync(join(root, "app", "(dashboard)", "channels", "rfq-handoffs", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation-registry.ts"), "utf8");
const demoApi = readFileSync(join(root, "lib", "demo-api.ts"), "utf8");
const demoDashboard = readFileSync(join(root, "components", "demo-dashboard.tsx"), "utf8");
const demoBff = readFileSync(
  join(root, "app", "api", "demo", "rfq-handoff", "route.ts"),
  "utf8"
);

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

test("api client does not manufacture browser permission headers (BFF session owns authority)", () => {
  assert.match(api, /usesBffTransport/);
  assert.match(api, /enrichDashboardRequestInit/);
  assert.match(api, /isDashboardApiAuthorityAvailable/);
  assert.doesNotMatch(api, /X-OrderPilot-Permissions/);
  assert.match(api, /Permissions are enforced by BFF session/);
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

test("workspace renders the schema-specific safe AI Work V1 projection", () => {
  assert.match(workspace, /AiWorkSchemaV1View suggestion=\{aiSuggestion\}/);
  assert.match(aiWorkApi, /AI_WORK_SCHEMA_V1_REQUEST_SUMMARY/);
  assert.match(aiWorkApi, /AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION/);
  assert.match(aiWorkApi, /AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT/);
  assert.match(aiWorkApi, /AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION/);
  assert.match(aiWorkSchemaView, /switch \(suggestion\.schemaVersion\)/);
  assert.match(aiWorkSchemaView, /suggestion\.summary/);
  assert.match(aiWorkSchemaView, /suggestion\.nextActionCandidates/);
  assert.match(aiWorkSchemaView, /suggestion\.displayFields/);
  assert.match(aiWorkSchemaView, /suggestion\.riskFlags/);
  assert.match(aiWorkSchemaView, /suggestion\.evidence/);
  assert.match(aiWorkSchemaView, /ADVISORY ONLY/);
  assert.match(aiWorkSchemaView, /Human approval required/);
  assert.match(aiWorkSchemaView, /safety\.externalExecution/);
  assert.match(aiWorkSchemaView, /safety\.connectorCall/);
  assert.match(aiWorkSchemaView, /safety\.outbox/);
  assert.doesNotMatch(
    aiWorkSchemaView,
    /generatedText|structuredPayloadJson|evidenceRefsJson|strategyVersion/
  );
});

test("AI Work client and renderer expose no forbidden provider or authority fields", () => {
  const suggestionType = aiWorkApi.slice(
    aiWorkApi.indexOf("export type AiWorkSuggestion ="),
    aiWorkApi.indexOf("export type AiWorkDecisionRequest")
  );
  assert.match(suggestionType, /schemaVersion: AiWorkSchemaVersion/);
  assert.match(suggestionType, /safety: AiWorkSafety/);
  assert.doesNotMatch(
    suggestionType,
    /tenantId|actorId|idempotencyKey|rawPayload|payloadJson|prompt|apiKey|credential|auditEventId|stackTrace|strategyVersion/
  );
  assert.doesNotMatch(aiWorkApi, /error instanceof Error \? error\.message/);
});

test("api client has no manual handoff create or ERP action", () => {
  assert.doesNotMatch(api, /createRfqHandoff|createHandoff/);
  assert.doesNotMatch(api, /createOrder|approveQuote|approveOrder|erpSync|updateInventory|priceUpdate/i);
});

test("demo RFQ browser action targets the same-origin BFF with no authority payload", () => {
  const start = demoApi.indexOf("export function sendDemoTelegramRfq");
  const end = demoApi.indexOf("\n}", start);
  const action = demoApi.slice(start, end);

  assert.match(action, /requestDashboardJson<DemoRfqHandoffResponse>\("\/api\/demo\/rfq-handoff"\)/);
  assert.doesNotMatch(action, /\/api\/v1\/bot\/telegram\/webhook/);
  assert.doesNotMatch(action, /JSON\.stringify|body:|headers:/);
  assert.doesNotMatch(action, /update_id|message_id|chat:/);
  assert.doesNotMatch(
    action,
    /tenantId|actorId|sourceId|channelConnectionId|status|approvalStatus|executionStatus|audit|idempotency|rawPayload/
  );
});

test("demo RFQ BFF resolves authority server-side and calls the managed core path", () => {
  assert.match(demoBff, /CORE_PATH = "\/api\/v1\/demo\/rfq-handoff"/);
  assert.match(demoBff, /process\.env\.ORDERPILOT_DEMO_TENANT_ID/);
  assert.match(demoBff, /process\.env\.ORDERPILOT_DEMO_MODE === "true"/);
  assert.doesNotMatch(demoBff, /NEXT_PUBLIC_/);
  assert.match(demoBff, /"X-OrderPilot-Permissions": DEMO_ACTION_PERMISSION/);
  assert.match(demoBff, /DEMO_ACTION_PERMISSION = "ADMIN_SETTINGS_MANAGE"/);
  assert.match(demoBff, /submittedBody && submittedBody !== "\{\}"/);
  assert.doesNotMatch(demoBff, /request\.json\(\)/);
});

test("demo RFQ public contract exposes only the opaque handoff workflow result", () => {
  const start = demoApi.indexOf("export type DemoRfqHandoffResponse");
  const end = demoApi.indexOf("\n};", start);
  const contract = demoApi.slice(start, end);

  assert.match(contract, /handoffId: string/);
  assert.match(contract, /status: string/);
  assert.match(contract, /message: string/);
  assert.doesNotMatch(
    contract,
    /tenantId|actorId|sourceId|channelConnectionId|eventId|auditId|idempotency|rawPayload|credentials/
  );
  assert.match(demoDashboard, /rfqResult\?\.handoffId/);
  assert.doesNotMatch(
    demoDashboard,
    /rfqResult\?\.(tenantId|actorId|sourceId|channelConnectionId|eventId|auditId|rawPayload)/
  );
});

test("demo RFQ BFF and client map failures without returning raw backend errors", () => {
  assert.match(demoBff, /SAFE_FAILURE_MESSAGE/);
  assert.doesNotMatch(demoBff, /NextResponse\.json\([^)]*text/s);
  assert.doesNotMatch(demoApi, /error instanceof Error \? error\.message/);
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
  assert.doesNotMatch(api, /"X-OrderPilot-Permissions": QUOTE_ACTION/);
  assert.match(api, /enrichDashboardRequestInit/);
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
  assert.doesNotMatch(api, /"X-OrderPilot-Permissions": QUOTE_ACTION/);
  assert.match(api, /usesBffTransport/);
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

test("RFQ client maps runtime rate-limit/backpressure denial to a bounded safe message", () => {
  // OP-CAP-27B: 429/503 runtime-control denial renders a safe, retry-oriented message and leaks no
  // quota bucket, redis key, retry-after threshold, or raw guard state.
  assert.match(api, /case 429:/);
  assert.match(api, /case 503:/);
  assert.match(api, /Runtime capacity is busy right now\. Please retry this RFQ action in a moment\./);
  assert.doesNotMatch(api, /quotaBucket|redisKey|retryAfterSeconds|jti|nonce/);
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
