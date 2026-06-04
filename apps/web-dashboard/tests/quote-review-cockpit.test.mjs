import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const cockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");
const queuePage = readFileSync(join(root, "app", "(dashboard)", "quote-review", "page.tsx"), "utf8");
const detailPage = readFileSync(join(root, "app", "(dashboard)", "quote-review", "[quoteId]", "page.tsx"), "utf8");

test("quote review API client exposes Stage 12C endpoints", () => {
  assert.match(apiClient, /getQuoteReviewQueue/);
  assert.match(apiClient, /getQuoteReviewDetail/);
  assert.match(apiClient, /resolveQuoteReviewIssue/);
  assert.match(apiClient, /correctQuoteReviewLine/);
  assert.match(apiClient, /selectQuoteReviewSubstitute/);
  assert.match(apiClient, /\/api\/v1\/quote-review\/queue/);
  assert.match(apiClient, /\/api\/v1\/quote-review\/\$\{quoteId\}/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("quote review pages render queue and detail workflow", () => {
  assert.match(queuePage, /QuoteReviewQueue/);
  assert.match(detailPage, /QuoteReviewDetailWorkspace/);
  assert.match(cockpit, /Review Queue/);
  assert.match(cockpit, /Quote Review Detail/);
  assert.match(cockpit, /Validation Issue Panel/);
  assert.match(cockpit, /Source Evidence/);
  assert.match(cockpit, /Suggested Fix Panel/);
  assert.match(cockpit, /Substitute Candidates Panel/);
  assert.match(cockpit, /Audit Timeline/);
  assert.match(cockpit, /Approval Status/);
});

test("review UI does not show draft review quotes as final or fake fixed", () => {
  assert.doesNotMatch(cockpit, /final/i);
  assert.doesNotMatch(cockpit, /approved draft/i);
  assert.doesNotMatch(cockpit, /fake success/i);
  assert.match(cockpit, /persisted and revalidated/);
  assert.match(cockpit, /External ERP write: disabled \/ not executed/);
});

test("quote review links encode route data and avoid raw HTML sinks", () => {
  assert.match(cockpit, /buildQuoteReviewHref/);
  assert.match(cockpit, /URLSearchParams\(\{ tenantId \}\)/);
  assert.match(cockpit, /encodeURIComponent\(quoteId\)/);
  assert.match(cockpit, /UUID_RE\.test\(quoteId\)/);
  assert.match(cockpit, /reviewHref \? <a className="button secondary-button" href=\{reviewHref\}>Open Review<\/a> : <span className="muted-copy">Unavailable<\/span>/);
  assert.doesNotMatch(cockpit, /href=\{`\/quote-review\/\$\{row\.quoteId\}\?tenantId=\$\{tenantId\}`\}/);
  assert.doesNotMatch(cockpit, /dangerouslySetInnerHTML|innerHTML|outerHTML|insertAdjacentHTML|DOMParser|document\.write/);
});
