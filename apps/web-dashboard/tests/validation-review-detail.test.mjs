import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-14B — Operator Validation Review Workspace UI (source-inspection style, no live backend).
const root = process.cwd();
const reviewRoute = readFileSync(join(root, "app", "(dashboard)", "validations", "[id]", "review", "page.tsx"), "utf8");
const detailRoute = readFileSync(join(root, "app", "(dashboard)", "validations", "[id]", "page.tsx"), "utf8");
const components = readFileSync(join(root, "components", "validation-review-detail.tsx"), "utf8");
const apiClient = readFileSync(join(root, "lib", "validation-review-detail-api.ts"), "utf8");

// Raw advisory payload / document body / prompt / secret field markers that must never surface in the UI layer.
// Narrow to identifiers (not safety prose like "secret or stack trace") to mirror the draft-review test convention.
const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|rawMessage|promptText|untrustedUntilValidation|schemaVersion|apiKey|accessToken|bearerToken|TOP_SECRET/i;
// Forbidden business-write / external-execution / fake-approval controls.
const FORBIDDEN_CONTROLS =
  /Approve final|Create quote|Create order|Send to ERP|Sync external|Create invoice|prepareDraft|executeConnector|reserveInventory|approveApproval|correctReview/i;

test("review route exists under the existing validations slug", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "validations", "[id]", "review", "page.tsx")), true);
});

test("API helper calls the OP-CAP-14A review endpoints with tenant header (read-only)", () => {
  assert.match(apiClient, /getValidationReviewByRun/);
  assert.match(apiClient, /\/api\/v1\/validations\/\$\{validationRunId\}\/review/);
  assert.match(apiClient, /getValidationReviewByExtraction/);
  assert.match(apiClient, /\/api\/v1\/validations\/extractions\/\$\{extractionResultId\}\/review/);
  assert.match(apiClient, /dashboardRequestHeaders\(validationReviewDetailConfig\.tenantId\)/);
  assert.match(apiClient, /method: "GET"/);
  // No mutating verbs in the read-only client.
  assert.doesNotMatch(apiClient, /method: "POST"|method: "PATCH"|method: "PUT"|method: "DELETE"/);
});

test("page loads review by validationRunId and handles error/empty states", () => {
  assert.match(reviewRoute, /getValidationReviewByRun/);
  assert.match(reviewRoute, /ValidationReviewDetailView/);
  assert.match(reviewRoute, /form-message error/);
  assert.match(reviewRoute, /No validation review is available/);
  assert.match(reviewRoute, /DashboardShell/);
});

test("header renders validation status, routing decision and advisory-only marker", () => {
  assert.match(components, /Validation status:/);
  assert.match(components, /Routing:/);
  assert.match(components, /Advisory-only:/);
  assert.match(components, /Validation run id/);
  assert.match(components, /Extraction result id/);
});

test("extracted fields render value, normalized, confidence, status, evidence and issue marker", () => {
  assert.match(components, /Extracted fields/);
  assert.match(components, /Normalized/);
  assert.match(components, /Confidence/);
  assert.match(components, /evidenceRef/);
  assert.match(components, /issueMarker/);
});

test("line items table renders qty, UOM, confidence and validation status", () => {
  assert.match(components, /Line items/);
  assert.match(components, />Qty</);
  assert.match(components, />UOM</);
  assert.match(components, /Matched product/);
  assert.match(components, /l\.validationStatus/);
});

test("issues panel renders severity, code, target, blocking and explanation", () => {
  assert.match(components, /Validation issues/);
  assert.match(components, /i\.severity/);
  assert.match(components, /i\.code/);
  assert.match(components, /i\.targetType/);
  assert.match(components, /Blocking/);
  assert.match(components, /i\.message/);
});

test("source evidence renders bounded snippet with no full document/payload viewer", () => {
  assert.match(components, /Source evidence/);
  assert.match(components, /Bounded source snippets only/);
  assert.match(components, /evidence-snippet/);
  assert.match(components, /e\.snippet/);
});

test("audit timeline has a graceful empty state", () => {
  assert.match(components, /Audit timeline/);
  assert.match(components, /No audit activity has been recorded/);
  assert.match(components, /a\.action/);
  assert.doesNotMatch(components, /a\.(actorId|entityId)/);
});

test("allowed actions render as declarative hints with disabled/not-implemented state", () => {
  assert.match(components, /Allowed next actions/);
  assert.match(components, /declarative safety hints/);
  assert.match(components, /not yet implemented/);
  assert.match(components, /a\.enabled/);
  assert.match(components, /a\.requiredPermission/);
});

test("existing validation detail page links to the operator review", () => {
  assert.match(detailRoute, /\/validations\/\$\{id\}\/review/);
  assert.match(detailRoute, /Open operator review/);
});

test("no raw AI payload / prompt / secret markers and no fake business-write controls in the UI", () => {
  assert.doesNotMatch(components, RAW_PAYLOAD);
  assert.doesNotMatch(reviewRoute, RAW_PAYLOAD);
  assert.doesNotMatch(apiClient, RAW_PAYLOAD);
  assert.doesNotMatch(components, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(reviewRoute, FORBIDDEN_CONTROLS);
  assert.doesNotMatch(apiClient, FORBIDDEN_CONTROLS);
});
