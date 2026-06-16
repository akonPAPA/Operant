import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-15H — review-origin draft remediation lineage DETAIL page + API client (source-inspection style).
const root = process.cwd();
const api = readFileSync(join(root, "lib", "validation-review-draft-queue-api.ts"), "utf8");
const detailPage = readFileSync(
  join(root, "app", "(dashboard)", "workspace", "review-drafts", "[draftKind]", "[draftId]", "remediation-lineage", "page.tsx"),
  "utf8"
);

const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|promptText|apiKey|accessToken|bearerToken|stackTrace/i;
const FORBIDDEN =
  /\/api\/v1\/quotes|\/api\/v1\/orders|\/api\/v1\/products|\/api\/v1\/customers|\/api\/v1\/inventory|\/api\/v1\/pricing|\/api\/v1\/integrations|\/api\/v1\/connector|\/approve\b|\/reject\b|executeConnector|reserveInventory|finalizeOrder|sendToErp/i;

test("detail route page exists", () => {
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "review-drafts", "[draftKind]", "[draftId]", "remediation-lineage", "page.tsx")), true);
});

test("API helper calls only the 15H remediation-lineage endpoint with tenant header, read-only", () => {
  assert.match(api, /getReviewDraftRemediationLineage/);
  assert.match(api, /\/api\/v1\/validations\/review-drafts\/\$\{encodeURIComponent\(draftKind\)\}\/\$\{encodeURIComponent\(draftId\)\}\/remediation-lineage/);
  assert.match(api, /X-Tenant-Id/);
  assert.match(api, /method: "GET"/);
  assert.doesNotMatch(api, /method: "POST"|method: "PATCH"|method: "PUT"|method: "DELETE"/);
  assert.doesNotMatch(api, FORBIDDEN);
  assert.doesNotMatch(api, RAW_PAYLOAD);
});

test("API helper maps 403/404/400 to bounded user-safe messages", () => {
  assert.match(api, /403/);
  assert.match(api, /VALIDATION_READ required/);
  assert.match(api, /404/);
  assert.match(api, /was not found for this tenant/);
  assert.match(api, /400/);
  assert.match(api, /Unsupported draft kind/);
});

test("API detail type maps the DTO shape (counts, lines, unattached actions)", () => {
  assert.match(api, /ValidationReviewDraftRemediationLineageDetail/);
  assert.match(api, /available: boolean/);
  assert.match(api, /draftLineCount: number/);
  assert.match(api, /traceableDraftLineCount: number/);
  assert.match(api, /remediatedDraftLineCount: number/);
  assert.match(api, /correctionActionCount: number/);
  assert.match(api, /issueResolutionActionCount: number/);
  assert.match(api, /approvalActionCount: number/);
  assert.match(api, /lines: ValidationReviewDraftRemediationLineageLine\[\]/);
  assert.match(api, /unattachedActions: ValidationReviewDraftRemediationLineageUnattachedAction\[\]/);
  assert.match(api, /limitations: string\[\]/);
});

test("API line/action types expose stable ids + status only (no raw operator note)", () => {
  assert.match(api, /sourceLineAvailable: boolean/);
  assert.match(api, /correctionActions: ValidationReviewDraftRemediationLineageAction\[\]/);
  assert.match(api, /issueResolutionActions: ValidationReviewDraftRemediationLineageAction\[\]/);
  assert.match(api, /approvalActions: ValidationReviewDraftRemediationLineageAction\[\]/);
  assert.match(api, /relatedIssueId\?: string \| null/);
  assert.match(api, /relatedApprovalRequirementId\?: string \| null/);
  assert.doesNotMatch(api, /operatorNote\??:\s*string/);
});

test("detail page is server-rendered (no use client)", () => {
  assert.doesNotMatch(detailPage, /"use client"/);
});

test("detail page renders summary counts", () => {
  assert.match(detailPage, /getReviewDraftRemediationLineage/);
  assert.match(detailPage, /Remediated before draft:/);
  assert.match(detailPage, /data\.remediatedDraftLineCount/);
  assert.match(detailPage, /data\.correctionActionCount/);
  assert.match(detailPage, /data\.issueResolutionActionCount/);
  assert.match(detailPage, /data\.approvalActionCount/);
});

test("detail page renders per-line correction/resolution/approval labels", () => {
  assert.match(detailPage, /line\.correctionActions/);
  assert.match(detailPage, /line\.issueResolutionActions/);
  assert.match(detailPage, /line\.approvalActions/);
  assert.match(detailPage, /Per-line lineage/);
});

test("detail page renders limitation tokens", () => {
  assert.match(detailPage, /Limitations/);
  assert.match(detailPage, /data\.limitations\.map/);
});

test("detail page renders the unattached actions section", () => {
  assert.match(detailPage, /Unattached actions/);
  assert.match(detailPage, /data\.unattachedActions\.map/);
  assert.match(detailPage, /action\.category/);
  assert.match(detailPage, /action\.limitation/);
});

test("detail page handles available=false and error/empty states", () => {
  assert.match(detailPage, /data\.available \?/);
  assert.match(detailPage, /Remediation lineage is unavailable/);
  assert.match(detailPage, /form-message error/);
  assert.match(detailPage, /This draft has no lines/);
});

test("detail page is read-only and exposes no raw payload / forbidden write paths", () => {
  assert.doesNotMatch(detailPage, RAW_PAYLOAD);
  assert.doesNotMatch(detailPage, FORBIDDEN);
  assert.doesNotMatch(detailPage, /dangerouslySetInnerHTML/);
  assert.match(detailPage, /external execution is DISABLED/i);
});

test("detail page links back to draft workspace and source review", () => {
  assert.match(detailPage, /data\.workspacePath/);
  assert.match(detailPage, /data\.reviewPath/);
  assert.match(detailPage, /Open draft/);
  assert.match(detailPage, /Source review/);
});

// --- OP-CAP-15I: per-line remediation timeline ---

test("API line type exposes the normalized timeline", () => {
  assert.match(api, /LineageTimelineEntry/);
  assert.match(api, /category: "CORRECTION" \| "ISSUE_RESOLUTION" \| "APPROVAL"/);
  assert.match(api, /actionId: string/);
  assert.match(api, /createdAt: string/);
  assert.match(api, /timeline: LineageTimelineEntry\[\]/);
});

test("detail page renders a per-line timeline ordered by time", () => {
  assert.match(detailPage, /Remediation timeline/);
  assert.match(detailPage, /line\.timeline\.map/);
  assert.match(detailPage, /entry\.category/);
  assert.match(detailPage, /entry\.createdAt/);
  assert.match(detailPage, /entry\.summary/);
});

test("detail page shows an explicit empty timeline state", () => {
  assert.match(detailPage, /line\.timeline\.length > 0/);
  assert.match(detailPage, /No remediation actions were recorded for this draft/);
});

test("timeline rendering stays read-only and safe (no unsafe html, no raw payload)", () => {
  assert.doesNotMatch(detailPage, /dangerouslySetInnerHTML/);
  assert.doesNotMatch(detailPage, RAW_PAYLOAD);
});
