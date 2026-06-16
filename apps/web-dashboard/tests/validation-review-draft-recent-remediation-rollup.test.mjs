import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-15J — recent remediation rollup tile API client + queue-page tile (source-inspection style).
const root = process.cwd();
const api = readFileSync(join(root, "lib", "validation-review-draft-queue-api.ts"), "utf8");
const queuePage = readFileSync(join(root, "app", "(dashboard)", "workspace", "review-drafts", "page.tsx"), "utf8");
const detailPage = readFileSync(
  join(root, "app", "(dashboard)", "workspace", "review-drafts", "[draftKind]", "[draftId]", "remediation-lineage", "page.tsx"),
  "utf8"
);

const RAW_PAYLOAD = /resultJson|result_json|rawText|messageText|documentText|promptText|apiKey|accessToken|bearerToken|stackTrace/i;
const FORBIDDEN =
  /\/api\/v1\/quotes|\/api\/v1\/orders|\/api\/v1\/products|\/api\/v1\/customers|\/api\/v1\/inventory|\/api\/v1\/pricing|\/api\/v1\/integrations|\/api\/v1\/connector|\/approve\b|\/reject\b|executeConnector|reserveInventory|finalizeOrder|sendToErp/i;

test("queue files exist", () => {
  assert.equal(existsSync(join(root, "lib", "validation-review-draft-queue-api.ts")), true);
  assert.equal(existsSync(join(root, "app", "(dashboard)", "workspace", "review-drafts", "page.tsx")), true);
});

test("API path helper builds the recent remediation rollup endpoint with optional limit", () => {
  assert.match(api, /export function remediationRollupPath/);
  assert.match(api, /\/api\/v1\/validations\/review-drafts\/remediation-rollup/);
  assert.match(api, /limit !== undefined \? `\?limit=\$\{limit\}`/);
});

test("API helper is read-only, tenant-scoped, and maps 403/400 safely", () => {
  assert.match(api, /export async function getReviewDraftRecentRemediationRollup/);
  assert.match(api, /method: "GET"/);
  assert.match(api, /X-Tenant-Id/);
  assert.match(api, /403/);
  assert.match(api, /VALIDATION_READ required/);
  assert.match(api, /400/);
  assert.match(api, /Invalid recent remediation rollup request/);
  assert.doesNotMatch(api, FORBIDDEN);
  assert.doesNotMatch(api, RAW_PAYLOAD);
});

test("API rollup response type maps the DTO shape", () => {
  assert.match(api, /ValidationReviewDraftRecentRemediationRollupResponse/);
  assert.match(api, /inspectedDraftCount: number/);
  assert.match(api, /reviewOriginDraftCount: number/);
  assert.match(api, /lineageAvailableDraftCount: number/);
  assert.match(api, /lineageUnavailableDraftCount: number/);
  assert.match(api, /remediationActionCount: number/);
  assert.match(api, /correctionActionCount: number/);
  assert.match(api, /issueResolutionActionCount: number/);
  assert.match(api, /approvalActionCount: number/);
  assert.match(api, /latestRemediationActionAt\?: string \| null/);
  assert.match(api, /limitationCodes: string\[\]/);
  assert.match(api, /topLimitedDrafts: ValidationReviewDraftRecentRemediationRollupItem\[\]/);
});

test("queue page fetches the rollup in parallel and does not block the queue on failure", () => {
  assert.match(queuePage, /getReviewDraftRecentRemediationRollup/);
  assert.match(queuePage, /Promise\.all/);
  assert.match(queuePage, /rollupError/);
  assert.match(queuePage, /never break the queue/i);
});

test("queue page renders the recent remediation tile counts and latest timestamp", () => {
  assert.match(queuePage, /Recent remediation/);
  assert.match(queuePage, /inspectedDraftCount/);
  assert.match(queuePage, /reviewOriginDraftCount/);
  assert.match(queuePage, /lineageAvailableDraftCount/);
  assert.match(queuePage, /lineageUnavailableDraftCount/);
  assert.match(queuePage, /remediatedDraftLineCount/);
  assert.match(queuePage, /traceableDraftLineCount/);
  assert.match(queuePage, /remediationActionCount/);
  assert.match(queuePage, /latestRemediationActionAt/);
});

test("queue page renders rollup limitation code tokens", () => {
  assert.match(queuePage, /rollup\.limitationCodes\.length > 0/);
  assert.match(queuePage, /limitationCodes\.map\(\(code\) => <code/);
});

test("queue page renders an explicit empty rollup state and an error state", () => {
  assert.match(queuePage, /No recent review-draft remediation activity/);
  assert.match(queuePage, /Recent remediation summary is unavailable/);
});

test("rollup tile stays plain text — no raw ids, no operator note, no unsafe html", () => {
  assert.doesNotMatch(queuePage, /dangerouslySetInnerHTML/);
  assert.doesNotMatch(queuePage, RAW_PAYLOAD);
  assert.doesNotMatch(queuePage, FORBIDDEN);
});

test("15I queue-row rollup still renders alongside the 15J tile", () => {
  assert.match(queuePage, /item\.remediationRollup && item\.remediationRollup\.remediationLineageAvailable/);
  assert.match(queuePage, /remediationLineagePath\(item\.draftType, item\.draftId\)/);
});

test("15I detail timeline still renders", () => {
  assert.match(detailPage, /Remediation timeline/);
  assert.match(detailPage, /line\.timeline\.map/);
});
