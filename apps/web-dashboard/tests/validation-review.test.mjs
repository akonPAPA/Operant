import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const listRoute = readFileSync(join(root, "app", "(dashboard)", "validation-review", "page.tsx"), "utf8");
const detailRoute = readFileSync(join(root, "app", "(dashboard)", "validation-review", "[reviewCaseId]", "page.tsx"), "utf8");
const workspace = readFileSync(join(root, "components", "validation-review-workspace.tsx"), "utf8");
const apiClient = readFileSync(join(root, "lib", "validation-review-api.ts"), "utf8");
const botApiClient = readFileSync(join(root, "lib", "bot-runtime-api.ts"), "utf8");
const botPage = readFileSync(join(root, "app", "(dashboard)", "bot-conversations", "page.tsx"), "utf8");
const botWorkspace = readFileSync(join(root, "components", "bot-conversations-workspace.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

test("validation review routes are present in dashboard navigation", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "validation-review", "page.tsx")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "validation-review", "[reviewCaseId]", "page.tsx")), true);
  assert.match(navigation, /label: "Validation Review"/);
  assert.match(navigation, /href: "\/validation-review"/);
});

test("review detail renders validation issues and risk sections", () => {
  assert.match(detailRoute, /ValidationReviewWorkspace/);
  assert.match(workspace, /Validation Issues/);
  assert.match(workspace, /Safety Flags/);
  assert.match(workspace, /Line Item Validation Results/);
  assert.match(workspace, /Approval Requirements/);
});

test("validation review page renders correction controls", () => {
  assert.match(workspace, /Correct UOM/);
  assert.match(workspace, /Correct quantity/);
  assert.match(workspace, /Select candidate/);
  assert.match(apiClient, /correctReviewUom/);
  assert.match(apiClient, /corrections\/uom/);
});

test("candidate picker renders backend candidates and maps selected product", () => {
  assert.match(workspace, /ProductCandidatePicker/);
  assert.match(workspace, /productCandidates/);
  assert.match(workspace, /mapReviewProduct/);
  assert.match(workspace, /onSelect\(event\.target\.value\)/);
});

test("override reason flow exists", () => {
  assert.match(workspace, /Override Reason/);
  assert.match(workspace, /Required reason for risky override/);
  assert.match(apiClient, /overrideReviewIssue/);
  assert.match(apiClient, /issues\/override/);
});

test("blocked substitute warning is visible", () => {
  assert.match(workspace, /BLOCKED_SUBSTITUTE/);
  assert.match(workspace, /Blocked substitute cannot be hidden by the UI|BLOCKED/);
  assert.match(workspace, /blocked substitute/i);
});

test("approve action calls the validation review approve API", () => {
  assert.match(apiClient, /approveValidationReviewCase/);
  assert.match(apiClient, /\/api\/v1\/validation-review\/\$\{reviewCaseId\}\/approve/);
  assert.match(workspace, /Approve review case/);
});

test("manager approval controls use backend decision endpoints", () => {
  assert.match(apiClient, /approveReviewApproval/);
  assert.match(apiClient, /rejectReviewApproval/);
  assert.match(apiClient, /approvals\/\$\{approvalRequestId\}\/approve/);
  assert.match(apiClient, /approvals\/\$\{approvalRequestId\}\/reject/);
  assert.match(workspace, /Approval decision reason/);
  assert.match(workspace, /Reject manager approval/);
});

test("readiness evaluator drives draft buttons and preview state", () => {
  assert.match(apiClient, /ReviewReadiness/);
  assert.match(workspace, /Draft Readiness/);
  assert.match(workspace, /readiness\?\.draftPreparationAllowed/);
  assert.match(workspace, /draftPreview\?\.readiness/);
  assert.match(workspace, /rejected approval/);
});

test("prepare draft quote handles backend rejection and shows the reason", () => {
  assert.match(apiClient, /prepareDraftQuote/);
  assert.match(apiClient, /prepare-draft-quote/);
  assert.match(apiClient, /blockingReasons/);
  assert.match(workspace, /setAction\(\{ status: "error", message: result\.error \}\)/);
  assert.match(workspace, /Backend validation\/review gates remain authoritative/);
});

test("draft preview panel renders blockers and line data", () => {
  assert.match(apiClient, /getDraftPreview/);
  assert.match(apiClient, /draft-preview/);
  assert.match(workspace, /Draft Quote Preview/);
  assert.match(workspace, /draftPreview\.blockingReasons/);
  assert.match(workspace, /draftPreview\?\.lines/);
});

test("prepare draft order success path displays success state", () => {
  assert.match(apiClient, /prepareDraftOrder/);
  assert.match(apiClient, /prepare-draft-order/);
  assert.match(workspace, /Prepare draft order/);
  assert.match(workspace, /\$\{label\} completed\./);
  assert.match(workspace, /form-message.*done/);
});

test("draft buttons are blocked when backend says not preparable", () => {
  assert.match(workspace, /draftPreparationAllowed/);
  assert.match(workspace, /blockingReasons/);
  assert.match(workspace, /disabled=\{action\.status === "loading" \|\| !canPrepare\}/);
});

test("review list uses tenant-scoped API client", () => {
  assert.match(listRoute, /listValidationReviewCases/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /\/api\/v1\/validation-review/);
});

test("audit timeline renders correction and blocked events", () => {
  assert.match(workspace, /Audit Timeline/);
  assert.match(workspace, /Correction History/);
  assert.match(workspace, /correctionHistory/);
  assert.match(workspace, /DRAFT_PREPARATION_BLOCKED|blockingReasons/);
});

test("bot conversations route renders runtime list and policy state", () => {
  assert.match(botPage, /BotConversationsWorkspace/);
  assert.match(botPage, /\/api\/v1\/bot-runtime\/telegram\/webhook/);
  assert.match(botWorkspace, /Conversation List/);
  assert.match(botWorkspace, /detectedIntent/);
  assert.match(botWorkspace, /policyDecision/);
  assert.match(botWorkspace, /requiresHumanReview/);
  assert.match(botWorkspace, /Runtime Boundary/);
});

test("bot simulate flow calls backend API client", () => {
  assert.match(botApiClient, /simulateBotMessage/);
  assert.match(botApiClient, /\/api\/v1\/bot-runtime\/messages\/simulate/);
  assert.match(botApiClient, /X-Tenant-Id/);
  assert.match(botWorkspace, /Simulate Telegram Message/);
  assert.match(botWorkspace, /suggestedSafeResponse/);
});

test("bot response draft panel renders operator-assisted stub state", () => {
  assert.match(botWorkspace, /Response Drafts/);
  assert.match(botApiClient, /createBotResponseDraft/);
  assert.match(botApiClient, /responses\/draft/);
  assert.match(botApiClient, /markBotResponseReady/);
  assert.match(botApiClient, /stubSendBotResponse/);
  assert.match(botWorkspace, /requiresOperatorReview/);
  assert.match(botWorkspace, /Local stub send/);
  assert.match(botWorkspace, /does not contact Telegram/);
});

test("bot review handoff state and action render safely", () => {
  assert.match(botApiClient, /createBotReviewHandoff/);
  assert.match(botApiClient, /review-handoff/);
  assert.match(botApiClient, /nextActions/);
  assert.match(botApiClient, /sourceConversationId/);
  assert.match(botWorkspace, /Create operator review handoff/);
  assert.match(botWorkspace, /linkedReviewCaseId/);
  assert.match(botWorkspace, /Operator handoff case/);
  assert.match(botWorkspace, /not validation-review ready/);
  assert.doesNotMatch(botWorkspace, /href=\{`\/validation-review\/\$\{item\.conversation\.linkedReviewCaseId\}`\}/);
  assert.match(botWorkspace, /handoffs\[0\]\?\.reason/);
  assert.match(botWorkspace, /sourceConversationId/);
  assert.match(botWorkspace, /Draft quote and draft order preparation are unavailable here/);
});

test("bot UI does not expose unsafe approval or external write controls", () => {
  assert.doesNotMatch(botWorkspace, /prepareDraftQuote|prepareDraftOrder|approveReviewApproval|executeConnector|reserveInventory|finalizeOrder|sendMessage/i);
  assert.match(botWorkspace, /cannot approve quotes/);
  assert.match(botWorkspace, /not a real Telegram send|send real Telegram messages/);
});
