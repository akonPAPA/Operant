import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-15A/15B — Validation Review → Draft bridge UI + draft visibility/selected-lines/operator-note
// (source-inspection style, no live backend).
const root = process.cwd();
const reviewRoute = readFileSync(join(root, "app", "(dashboard)", "validations", "[id]", "review", "page.tsx"), "utf8");
const controls = readFileSync(join(root, "components", "validation-review-draft-controls.tsx"), "utf8");
const draftApi = readFileSync(join(root, "lib", "validation-review-draft-command-api.ts"), "utf8");

const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|rawMessage|promptText|untrustedUntilValidation|schemaVersion|apiKey|accessToken|bearerToken|TOP_SECRET|stackTrace/i;
// Forbidden: final order/approval, ERP/connector, master-data, or non-15A/15B mutation endpoints.
const FORBIDDEN =
  /\/api\/v1\/quotes|\/api\/v1\/orders|\/api\/v1\/products|\/api\/v1\/customers|\/api\/v1\/inventory|\/api\/v1\/pricing|\/api\/v1\/integrations|\/api\/v1\/connector|\/approve\b|\/reject\b|executeConnector|reserveInventory|finalizeOrder|sendToErp/i;

test("draft command files exist", () => {
  assert.equal(existsSync(join(root, "lib", "validation-review-draft-command-api.ts")), true);
  assert.equal(existsSync(join(root, "components", "validation-review-draft-controls.tsx")), true);
});

test("draft API helper uses only the 15A/15B draft endpoints with tenant header", () => {
  assert.match(draftApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/draft-quote/);
  assert.match(draftApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/draft-order/);
  assert.match(draftApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/draft-status/);
  assert.match(draftApi, /X-Tenant-Id/);
  assert.match(draftApi, /NEXT_PUBLIC_CORE_API_URL/);
  assert.match(draftApi, /method: "POST"/);
  assert.match(draftApi, /method: "GET"/);
  assert.match(draftApi, /createDraftQuoteFromReview/);
  assert.match(draftApi, /createDraftOrderFromReview/);
  assert.match(draftApi, /getValidationReviewDraftStatus/);
});

test("draft API helper has no PATCH/PUT/DELETE and no forbidden/non-15A endpoints", () => {
  assert.doesNotMatch(draftApi, /method: "PATCH"|method: "PUT"|method: "DELETE"/);
  assert.doesNotMatch(draftApi, FORBIDDEN);
});

test("draft API helper maps 403/404/409/400 to bounded user-safe messages (no raw dump)", () => {
  assert.match(draftApi, /403/);
  assert.match(draftApi, /REVIEW_ACTION required/);
  assert.match(draftApi, /404/);
  assert.match(draftApi, /409/);
  assert.match(draftApi, /blockingReasons/);
  assert.match(draftApi, /400 and others/);
  assert.doesNotMatch(draftApi, RAW_PAYLOAD);
});

test("draft API helper builds a body with selectedLineIds and operatorNote", () => {
  assert.match(draftApi, /selectedLineIds/);
  assert.match(draftApi, /operatorNote/);
  assert.match(draftApi, /body\.selectedLineIds = options\.selectedLineIds/);
  // operatorNote is trimmed before sending.
  assert.match(draftApi, /options\.operatorNote\.trim\(\)/);
});

test("draft controls is a client component wired to the 15A/15B helpers", () => {
  assert.match(controls, /"use client"/);
  assert.match(controls, /createDraftQuoteFromReview/);
  assert.match(controls, /createDraftOrderFromReview/);
  assert.match(controls, /useRouter/);
  assert.match(controls, /router\.refresh\(\)/);
});

test("create draft quote and order buttons render when no draft exists", () => {
  assert.match(controls, /Create draft quote/);
  assert.match(controls, /Create draft order/);
  assert.match(controls, /alreadyCreated \? \(/);
});

test("existing draft badge/link renders and disables create when a draft exists", () => {
  assert.match(controls, /initialDraftStatus/);
  assert.match(controls, /already created/);
  assert.match(controls, /workspacePath/);
  assert.match(controls, /Open draft/);
  // create is disabled when a draft already exists (alreadyCreated feeds disableCreate).
  assert.match(controls, /const alreadyCreated = existing !== null/);
  assert.match(controls, /disableCreate = blocked \|\| submitting \|\| alreadyCreated \|\| selectionInvalid/);
  assert.match(controls, /disabled=\{disableCreate\}/);
});

test("buttons are blocked when unresolved blocking issues exist", () => {
  assert.match(controls, /blockingIssueCount/);
  assert.match(controls, /const blocked = blockingCount > 0/);
  assert.match(controls, /must be resolved first/);
});

test("selected-line subset mode is supported with a client guard for empty selection", () => {
  assert.match(controls, /selectionMode/);
  assert.match(controls, /selectedLineIds/);
  assert.match(controls, /Create from selected lines only/);
  assert.match(controls, /selectionInvalid = useMemo\(\(\) => selectionMode && selectedLineIds\.length === 0/);
  assert.match(controls, /line\(s\) selected/);
});

test("operator note textarea is bounded and rendered as text (no raw HTML)", () => {
  assert.match(controls, /Operator note \(optional\)/);
  assert.match(controls, /<textarea/);
  assert.match(controls, /maxLength=\{MAX_OPERATOR_NOTE\}/);
  assert.match(controls, /operatorNote\.length\}\/\{MAX_OPERATOR_NOTE\}/);
  assert.doesNotMatch(controls, /dangerouslySetInnerHTML/);
});

test("alreadyExisted/success reflects existing draft via backend response (no fake state)", () => {
  assert.match(controls, /alreadyExisted/);
  assert.match(controls, /setExisting\(\{ draftType: result\.data\.draftType/);
  assert.doesNotMatch(controls, RAW_PAYLOAD);
  assert.doesNotMatch(controls, FORBIDDEN);
});

test("loading/success/error states are handled", () => {
  assert.match(controls, /status: "loading"/);
  assert.match(controls, /status: "success"/);
  assert.match(controls, /status: "error"/);
  assert.match(controls, /form-message/);
});

test("page stays server-rendered and passes draft status to the narrow client controls", () => {
  assert.doesNotMatch(reviewRoute, /"use client"/);
  assert.match(reviewRoute, /getValidationReviewDraftStatus/);
  assert.match(reviewRoute, /initialDraftStatus=\{draftStatus\}/);
  assert.match(reviewRoute, /ValidationReviewDraftControls/);
  assert.match(reviewRoute, /ValidationReviewDetailView/);
  assert.match(reviewRoute, /ValidationReviewActionsClient/);
});
