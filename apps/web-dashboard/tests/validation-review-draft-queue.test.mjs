import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-15C — review-origin draft queue (lite) page + API client (source-inspection style, no live backend).
const root = process.cwd();
const queueApi = readFileSync(join(root, "lib", "validation-review-draft-queue-api.ts"), "utf8");
const queuePage = readFileSync(join(root, "app", "(dashboard)", "workspace", "review-drafts", "page.tsx"), "utf8");

const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|promptText|apiKey|accessToken|bearerToken|stackTrace/i;
const FORBIDDEN =
  /\/api\/v1\/quotes|\/api\/v1\/orders|\/api\/v1\/products|\/api\/v1\/customers|\/api\/v1\/inventory|\/api\/v1\/pricing|\/api\/v1\/integrations|\/api\/v1\/connector|\/approve\b|\/reject\b|executeConnector|reserveInventory|finalizeOrder|sendToErp/i;

test("queue files exist", () => {
  assert.equal(existsSync(join(root, "lib", "validation-review-draft-queue-api.ts")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "review-drafts", "page.tsx")), true);
});

test("queue API helper uses only the 15C review-drafts endpoint with tenant header", () => {
  assert.match(queueApi, /\/api\/v1\/validations\/review-drafts/);
  assert.match(queueApi, /X-Tenant-Id/);
  assert.match(queueApi, /method: "GET"/);
  assert.match(queueApi, /getReviewDraftQueue/);
});

test("queue API helper is read-only and has no forbidden/non-15C endpoints", () => {
  assert.doesNotMatch(queueApi, /method: "POST"|method: "PATCH"|method: "PUT"|method: "DELETE"/);
  assert.doesNotMatch(queueApi, FORBIDDEN);
  assert.doesNotMatch(queueApi, RAW_PAYLOAD);
});

test("queue API helper maps 403/400 to bounded user-safe messages", () => {
  assert.match(queueApi, /403/);
  assert.match(queueApi, /VALIDATION_READ required/);
  assert.match(queueApi, /400/);
  assert.match(queueApi, /Invalid review-draft queue filter/);
});

test("queue item type exposes notePresent only (no raw operator note field)", () => {
  assert.match(queueApi, /operatorNotePresent: boolean/);
  assert.doesNotMatch(queueApi, /operatorNote\??:\s*string/);
});

test("queue page is server-rendered (no use client)", () => {
  assert.doesNotMatch(queuePage, /"use client"/);
});

test("queue page renders quote/order rows with workspace and review links", () => {
  assert.match(queuePage, /getReviewDraftQueue/);
  assert.match(queuePage, /item\.workspacePath/);
  assert.match(queuePage, /item\.reviewPath/);
  assert.match(queuePage, /Open draft/);
  assert.match(queuePage, /Review/);
});

test("queue page renders an empty state", () => {
  assert.match(queuePage, /No drafts have been created from validation reviews/);
});

test("queue page shows external execution DISABLED safety wording and no raw note", () => {
  assert.match(queuePage, /external execution is DISABLED/i);
  assert.match(queuePage, /operatorNotePresent/);
  assert.doesNotMatch(queuePage, RAW_PAYLOAD);
  assert.doesNotMatch(queuePage, FORBIDDEN);
});

// --- OP-CAP-15G: read-only remediation outcome summary ---

test("queue API type exposes the read-only remediation summary (counts/booleans only)", () => {
  assert.match(queueApi, /ValidationReviewDraftRemediationSummary/);
  assert.match(queueApi, /available: boolean/);
  assert.match(queueApi, /draftLineCount: number/);
  assert.match(queueApi, /remediatedDraftLineCount: number/);
  assert.match(queueApi, /correctionActionCount: number/);
  assert.match(queueApi, /issueResolutionActionCount: number/);
  assert.match(queueApi, /approvalActionCount: number/);
  assert.match(queueApi, /remediationSummary\?: ValidationReviewDraftRemediationSummary \| null/);
  // No raw note / raw evidence text in the type.
  assert.doesNotMatch(queueApi, /operatorNote\??:\s*string/);
});

test("queue page renders line count and remediated-before-draft summary when available", () => {
  assert.match(queuePage, /item\.lineCount/);
  assert.match(queuePage, /remediationSummary && item\.remediationSummary\.available/);
  assert.match(queuePage, /Remediated before draft:/);
  assert.match(queuePage, /remediatedDraftLineCount/);
  assert.match(queuePage, /remediationActionsText/);
});

test("queue page marks remediation lineage unavailable when backend says so", () => {
  assert.match(queuePage, /Remediation lineage unavailable/);
});

test("remediation summary is plain text — no raw ids and no operator note content", () => {
  // Action text helper builds counts only; never interpolates ids or note content.
  assert.match(queuePage, /correctionActionCount > 0.*correction/s);
  assert.doesNotMatch(queuePage, /remediationSummary\.\w*[Ii]d/);
  assert.doesNotMatch(queuePage, /dangerouslySetInnerHTML/);
});

test("workspace and source-review links remain unchanged", () => {
  assert.match(queuePage, /item\.workspacePath/);
  assert.match(queuePage, /item\.reviewPath/);
  assert.match(queuePage, /Open draft/);
  assert.match(queuePage, /Review/);
});

// --- OP-CAP-15H: link to the read-only remediation lineage detail ---

test("queue page links each draft row to its remediation lineage detail", () => {
  assert.match(queuePage, /remediationLineagePath\(item\.draftType, item\.draftId\)/);
  assert.match(queuePage, /Remediation lineage/);
});

test("queue API exposes the lineage path helper", () => {
  assert.match(queueApi, /export function remediationLineagePath/);
  assert.match(queueApi, /\/workspace\/review-drafts\//);
  assert.match(queueApi, /remediation-lineage/);
});

// --- OP-CAP-15I: compact remediation rollup on the queue ---

test("queue API type exposes the read-only remediation rollup", () => {
  assert.match(queueApi, /ValidationReviewDraftRemediationRollup/);
  assert.match(queueApi, /remediationLineageAvailable: boolean/);
  assert.match(queueApi, /remediationActionCount: number/);
  assert.match(queueApi, /remediatedLineCount: number/);
  assert.match(queueApi, /traceableLineCount: number/);
  assert.match(queueApi, /limitationCodes: string\[\]/);
  assert.match(queueApi, /latestRemediationActionAt\?: string \| null/);
  assert.match(queueApi, /remediationRollup\?: ValidationReviewDraftRemediationRollup \| null/);
});

test("queue page renders the compact rollup counts and latest timestamp", () => {
  assert.match(queuePage, /remediationRollup && item\.remediationRollup\.remediationLineageAvailable/);
  assert.match(queuePage, /remediationActionCount/);
  assert.match(queuePage, /remediatedLineCount/);
  assert.match(queuePage, /traceableLineCount/);
  assert.match(queuePage, /latestRemediationActionAt/);
});

test("queue page renders rollup limitation codes and unavailable state as code tokens", () => {
  assert.match(queuePage, /No remediation rollup/);
  assert.match(queuePage, /limitationCodes\.length > 0/);
  assert.match(queuePage, /limitationCodes\.map\(\(code\) => <code/);
});

test("rollup rendering stays plain text — no raw ids, no operator note, no unsafe html", () => {
  assert.doesNotMatch(queuePage, /remediationRollup\.\w*[Ii]d\b/);
  assert.doesNotMatch(queuePage, /dangerouslySetInnerHTML/);
  assert.doesNotMatch(queuePage, RAW_PAYLOAD);
});
