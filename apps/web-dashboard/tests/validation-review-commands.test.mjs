import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { assertApiBaseUrlSource, assertTenantScopedClientSource } from "./lib/api-client-contract.mjs";

// OP-CAP-14D — Frontend wiring for operator validation review commands (source-inspection style, no live backend).
const root = process.cwd();
const reviewRoute = readFileSync(join(root, "app", "(dashboard)", "validations", "[id]", "review", "page.tsx"), "utf8");
const actions = readFileSync(join(root, "components", "validation-review-actions.tsx"), "utf8");
const commandApi = readFileSync(join(root, "lib", "validation-review-command-api.ts"), "utf8");
const detailComponents = readFileSync(join(root, "components", "validation-review-detail.tsx"), "utf8");

// Raw advisory payload / document / prompt / secret field identifiers that must never surface.
const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|rawMessage|promptText|untrustedUntilValidation|schemaVersion|apiKey|accessToken|bearerToken|TOP_SECRET|stackTrace/i;
// Forbidden non-14C mutation / external-write / final-decision endpoints and controls.
const FORBIDDEN_ENDPOINTS =
  /\/api\/v1\/quotes|\/api\/v1\/orders|\/api\/v1\/workspace|\/api\/v1\/products|\/api\/v1\/customers|\/api\/v1\/inventory|\/api\/v1\/pricing|\/api\/v1\/integrations|\/api\/v1\/connector|\/approve\b|\/reject\b|prepareDraft|executeConnector|reserveInventory/i;

test("command API helper file exists", () => {
  assert.equal(existsSync(join(root, "lib", "validation-review-command-api.ts")), true);
  assert.equal(existsSync(join(root, "components", "validation-review-actions.tsx")), true);
});

test("command API helper uses only the three 14C POST endpoints with tenant header", () => {
  assert.match(commandApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/corrections/);
  assert.match(commandApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/issues\/\$\{issueId\}\/resolution/);
  assert.match(commandApi, /\/api\/v1\/validations\/\$\{validationRunId\}\/review\/approval-requests/);
  assertTenantScopedClientSource(commandApi);
  assertApiBaseUrlSource(commandApi);
  assert.match(commandApi, /enrichDashboardRequestInit/);
  assert.match(commandApi, /method: "POST"/);
  assert.match(commandApi, /submitValidationReviewCorrection/);
  assert.match(commandApi, /resolveValidationReviewIssue/);
  assert.match(commandApi, /requestValidationReviewApproval/);
});

test("command API helper has no PATCH/PUT/DELETE and no non-14C mutation endpoints", () => {
  assert.doesNotMatch(commandApi, /method: "PATCH"|method: "PUT"|method: "DELETE"/);
  assert.doesNotMatch(commandApi, FORBIDDEN_ENDPOINTS);
});

test("command API helper maps 403/404/400 to bounded user-safe messages (no raw dump)", () => {
  assert.match(commandApi, /403/);
  assert.match(commandApi, /REVIEW_ACTION required/);
  assert.match(commandApi, /404/);
  assert.match(commandApi, /no longer available/);
  assert.doesNotMatch(commandApi, RAW_PAYLOAD);
});

test("actions component is a client component wired to the 14C command helpers", () => {
  assert.match(actions, /"use client"/);
  assert.match(actions, /submitValidationReviewCorrection/);
  assert.match(actions, /resolveValidationReviewIssue/);
  assert.match(actions, /requestValidationReviewApproval/);
  assert.match(actions, /useRouter/);
  assert.match(actions, /router\.refresh\(\)/);
});

test("correction UI requires a reason and a bounded value (no raw JSON editing)", () => {
  assert.match(actions, /A correction reason is required/);
  assert.match(actions, /maxLength=\{MAX_VALUE\}/);
  assert.match(actions, /maxLength=\{MAX_REASON\}/);
  assert.match(actions, /maxLength=\{MAX_UOM\}/);
  // No raw/JSON blob editing surface.
  assert.doesNotMatch(actions, /<textarea/);
  assert.doesNotMatch(actions, /JSON\.parse|resultJson|rawJson/);
});

test("line item correction only exposes safe quantity/UOM fields", () => {
  assert.match(actions, /correctedQuantity/);
  assert.match(actions, /correctedUom/);
  assert.match(actions, /Quantity must be a positive number/);
});

test("issue controls expose Resolve / Ignore / Escalate as backend-backed commands", () => {
  assert.match(actions, /resolve\("RESOLVED"\)/);
  assert.match(actions, /resolve\("IGNORED"\)/);
  assert.match(actions, /resolve\("ESCALATED"\)/);
  assert.match(actions, /A reason is required to resolve an issue/);
});

test("approval request control exists but performs no approve/reject decision", () => {
  assert.match(actions, /Request approval/);
  assert.match(actions, /requestValidationReviewApproval/);
  assert.doesNotMatch(actions, /\/approve\b|\/reject\b|approveApproval|rejectApproval/);
});

test("buttons disable while submitting and feedback is user-safe (no raw payload/secret)", () => {
  assert.match(actions, /disabled=\{submitting\}/);
  assert.match(actions, /status: "loading"/);
  assert.match(actions, /status: "success"/);
  assert.match(actions, /status: "error"/);
  assert.doesNotMatch(actions, RAW_PAYLOAD);
  assert.doesNotMatch(actions, FORBIDDEN_ENDPOINTS);
});

test("review page keeps its route, the read-only view, and adds the actions client", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "validations", "[id]", "review", "page.tsx")), true);
  assert.match(reviewRoute, /ValidationReviewDetailView/);
  assert.match(reviewRoute, /ValidationReviewActionsClient/);
  assert.match(reviewRoute, /getValidationReviewByRun/);
  assert.match(reviewRoute, /No validation review is available/);
});

test("read-only detail panels remain present and unchanged in scope", () => {
  assert.match(detailComponents, /Extracted fields/);
  assert.match(detailComponents, /Line items/);
  assert.match(detailComponents, /Validation issues/);
  assert.match(detailComponents, /Source evidence/);
  assert.match(detailComponents, /Audit timeline/);
  // The read-only component must not itself perform mutations.
  assert.doesNotMatch(detailComponents, /method: "POST"|submitValidationReviewCorrection|resolveValidationReviewIssue/);
});
