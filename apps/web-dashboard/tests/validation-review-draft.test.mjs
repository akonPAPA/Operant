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
const actions = readFileSync(join(root, "components", "validation-review-actions.tsx"), "utf8");

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

// --- OP-CAP-15C: advisory per-line draftability hints ---

test("draft API helper exposes read-only draftability with the 15C endpoint", () => {
  assert.match(draftApi, /getValidationReviewDraftability/);
  assert.match(draftApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/draftability/);
  assert.match(draftApi, /VALIDATION_READ required/);
  // Draftability stays a read — no POST/PATCH for it.
  assert.match(draftApi, /method: "GET"/);
});

test("controls render a compact line readiness section with status pills", () => {
  assert.match(controls, /Line readiness/);
  assert.match(controls, /readinessPill/);
  assert.match(controls, /Already drafted/);
  assert.match(controls, /Blocked/);
  assert.match(controls, /Warning/);
  assert.match(controls, /Ready/);
});

test("blocked or already-drafted lines are not selectable", () => {
  assert.match(controls, /function lineSelectable/);
  assert.match(controls, /hint\.severity !== "BLOCKED" && !hint\.alreadyDrafted/);
  // toggle refuses non-selectable lines and the checkbox is disabled for them.
  assert.match(controls, /if \(!lineSelectable\(lineItemId\)\) return;/);
  assert.match(controls, /disabled=\{disabledLine\}/);
});

test("draftability reasons render as text, never HTML", () => {
  assert.match(controls, /reasonText/);
  assert.match(controls, /REASON_TEXT/);
  assert.doesNotMatch(controls, /dangerouslySetInnerHTML/);
});

test("default selection excludes non-draftable lines (advisory)", () => {
  assert.match(controls, /detail\.lineItems[\s\S]*\.filter\(\(id\) =>/);
  assert.match(controls, /draftability\?\.lines\.find/);
});

test("page fetches draftability server-side and passes it to the narrow client controls", () => {
  assert.doesNotMatch(reviewRoute, /"use client"/);
  assert.match(reviewRoute, /getValidationReviewDraftability/);
  assert.match(reviewRoute, /draftability=\{draftability\}/);
});

// --- OP-CAP-15D: operator-actionable remediation links ---

test("draftability API type exposes machine-readable remediation metadata", () => {
  assert.match(draftApi, /ValidationReviewLineRemediation/);
  assert.match(draftApi, /reasonCode/);
  assert.match(draftApi, /remediationType/);
  assert.match(draftApi, /targetIssueId/);
  assert.match(draftApi, /targetLineItemId/);
  assert.match(draftApi, /recommendedAction/);
  assert.match(draftApi, /remediations: ValidationReviewLineRemediation\[\]/);
});

test("blocked/warning lines render compact remediation action links", () => {
  assert.match(controls, /remediationLabel/);
  assert.match(controls, /Resolve validation issue/);
  assert.match(controls, /Correct line item/);
  assert.match(controls, /Correct extracted field/);
  assert.match(controls, /Request approval/);
  assert.match(controls, /View issue/);
  // Only lines that actually carry remediations show a link.
  assert.match(controls, /hint\.remediations\.length > 0/);
});

test("remediation links reuse the existing OP-CAP-14C controls (no new modal)", () => {
  // Link target is built from the existing operator-review-actions panel anchor (OP-CAP-15E deep link).
  assert.match(controls, /REMEDIATION_ACTIONS_ANCHOR = "#operator-review-actions"/);
  assert.match(controls, /href=\{remediationHref\(pathname, remediation\)\}/);
  assert.match(controls, /\$\{REMEDIATION_ACTIONS_ANCHOR\}/); // anchor is appended to the deep-link href
  assert.match(actions, /id="operator-review-actions"/);
  // No bespoke remediation mutation/modal in the draft controls.
  assert.doesNotMatch(controls, /remediationModal|RemediationModal|submitRemediation/);
});

test("remediation text is escaped React (no dangerous HTML)", () => {
  assert.doesNotMatch(controls, /dangerouslySetInnerHTML/);
  // recommendedAction is surfaced via a plain title attribute, not raw HTML.
  assert.match(controls, /title=\{remediation\.recommendedAction\}/);
});

test("after a successful 14C action the existing refresh path is invoked", () => {
  assert.match(actions, /useRouter/);
  // 15F: success goes through applied(), which still ends in the existing router.refresh().
  assert.match(actions, /onApplied=\{applied\}/);
  assert.match(actions, /function applied\(\)[\s\S]*router\.refresh\(\);[\s\S]*\}/);
});

test("already-drafted lines offer no remediation (backend clears remediations; UI guards on length)", () => {
  // The UI renders links only when remediations is non-empty; the backend returns [] for already-drafted.
  assert.match(controls, /hint\.remediations\.length > 0 \?/);
});

// --- OP-CAP-15E: deep-target remediation selection ---

test("remediation link carries exact target issue/line metadata in search params", () => {
  assert.match(controls, /function remediationHref/);
  assert.match(controls, /reviewActionType/);
  assert.match(controls, /reviewActionIssueId.*remediation\.targetIssueId/s);
  assert.match(controls, /reviewActionLineItemId.*remediation\.targetLineItemId/s);
  // Link is an App Router navigation that scrolls to the existing panel via the hash.
  assert.match(controls, /import Link from "next\/link"/);
  assert.match(controls, /usePathname/);
});

test("14C panel reads deep-target search params", () => {
  assert.match(actions, /useSearchParams/);
  assert.match(actions, /reviewActionIssueId/);
  assert.match(actions, /reviewActionLineItemId/);
  assert.match(actions, /reviewActionType/);
});

test("14C preselects the matching issue when targetIssueId is present", () => {
  assert.match(actions, /useState\(targetIssueId \?\? ""\)/);
  // Re-targeting on a new remediation click is done by re-keying the control (no set-state-in-effect).
  assert.match(actions, /key=\{`issue-\$\{targetIssueId \?\? ""\}`\}/);
});

test("14C preselects the matching line when targetLineItemId is present (CORRECT_LINE)", () => {
  assert.match(actions, /reviewActionType === "CORRECT_LINE" && !!targetLineItemId/);
  assert.match(actions, /lineTargeted \? "LINE_ITEM" : "FIELD"/);
  assert.match(actions, /key=\{`corr-\$\{reviewActionType \?\? ""\}-\$\{targetLineItemId \?\? ""\}`\}/);
});

test("deep-target ids are validated against tenant-scoped detail (no bypass, no crash)", () => {
  // Only ids that already exist in the backend-provided, tenant-scoped detail are accepted.
  assert.match(actions, /detail\.issues\.some\(\(i\) => i\.issueId === rawIssueId\) \? rawIssueId : null/);
  assert.match(actions, /detail\.lineItems\.some\(\(l\) => l\.lineItemId === rawLineItemId\) \? rawLineItemId : null/);
});

test("manual operator selection still works when no remediation target is present", () => {
  // Defaults fall back to FIELD / empty when no target params are supplied.
  assert.match(actions, /lineTargeted \? "LINE_ITEM" : "FIELD"/);
  assert.match(actions, /useState\(targetIssueId \?\? ""\)/);
});

test("completing the existing 14C action still uses the existing refresh path", () => {
  assert.match(actions, /onApplied=\{applied\}/);
  assert.match(actions, /router\.refresh\(\)/);
});

test("ready lines build no deep-target link (no remediations to map)", () => {
  // remediation links are only rendered from hint.remediations, which is empty for ready/already-drafted.
  assert.match(controls, /hint\.remediations\.map\(\(remediation\)/);
});

// --- OP-CAP-15F: post-remediation continuity ---

test("draft controls section carries the continuity anchor", () => {
  assert.match(controls, /id="validation-review-draft-controls"/);
});

test("successful deep-targeted 14C action sets return-to-draft params and scrolls to draft controls", () => {
  assert.match(actions, /reviewReturnToDraft/);
  assert.match(actions, /reviewReturnLineItemId/);
  assert.match(actions, /reviewReturnReason.*remediation-completed/);
  assert.match(actions, /#validation-review-draft-controls/);
  // Marker is set only for a validated deep-target line; otherwise behavior is unchanged.
  assert.match(actions, /if \(targetLineItemId\) \{/);
  assert.match(actions, /router\.replace\(/);
});

test("continuity affordance validates the returned line against tenant-scoped draftability", () => {
  assert.match(controls, /reviewReturnToDraft/);
  assert.match(controls, /reviewReturnLineItemId/);
  // Only ids present in the backend-provided draftability map are honored; misses yield null (no crash).
  assert.match(controls, /hintById\.get\(returnLineItemId\) \?\? null/);
});

test("affordance appears only for now-draftable lines (not BLOCKED, not already drafted)", () => {
  assert.match(controls, /!returnHint\.alreadyDrafted && returnHint\.severity !== "BLOCKED"/);
  assert.match(controls, /is now draftable\. Continue draft\./);
});

test("affordance offers selection through existing local selection logic only", () => {
  // Reuses toggleLine (which already guards lineSelectable); no force-selection, no new mutation.
  assert.match(controls, /onClick=\{\(\) => toggleLine\(continuityHint\.lineItemId\)\}/);
  assert.match(controls, /Select this line/);
  assert.match(controls, /selectionMode && !selectedLineIds\.includes\(continuityHint\.lineItemId\)/);
});

test("no affordance markup renders for blocked/drafted/missing return ids (single guarded render site)", () => {
  // The affordance is rendered solely behind the continuityHint guard.
  assert.match(controls, /\{continuityHint \? \(/);
  assert.doesNotMatch(controls, /dangerouslySetInnerHTML/);
});
