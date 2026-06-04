import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const cockpit = readFileSync(join(root, "components", "conversion-review-cockpit.tsx"), "utf8");
const listPage = readFileSync(join(root, "app", "(dashboard)", "conversion-review", "page.tsx"), "utf8");
const detailPage = readFileSync(join(root, "app", "(dashboard)", "conversion-review", "[attemptId]", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

test("conversion review client uses read-only quote review endpoints", () => {
  assert.match(apiClient, /getQuoteConversionAttempts/);
  assert.match(apiClient, /getQuoteConversionAttemptDetail/);
  assert.match(apiClient, /QuoteConversionAttemptReviewItem/);
  assert.match(apiClient, /QuoteConversionAttemptReviewDetail/);
  assert.match(apiClient, /\/api\/v1\/quote-review\/conversion-attempts/);
  assert.match(apiClient, /\/api\/v1\/quote-review\/conversion-attempts\/\$\{attemptId\}/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("conversion review routes render list and detail cockpit", () => {
  assert.match(listPage, /ConversionReviewList/);
  assert.match(detailPage, /ConversionReviewDetail/);
  assert.match(navigation, /Conversion Review/);
  assert.match(navigation, /\/conversion-review/);
  assert.match(cockpit, /Conversion Attempts/);
  assert.match(cockpit, /Validation Issues/);
  assert.match(cockpit, /Safe Metadata/);
  assert.match(cockpit, /Pre-draft/);
  assert.match(cockpit, /Draft-linked/);
});

test("conversion review UI remains read-only and avoids unsafe fields", () => {
  assert.match(cockpit, /Read-only conversion review/);
  assert.match(cockpit, /does not approve, reject, retry, correct, create quotes, execute connectors, or write to ERP\/1C/);
  assert.match(cockpit, /Raw payloads, raw message text, document text, webhook tokens, connector credentials, secrets, and raw AI output are not displayed/);
  assert.doesNotMatch(cockpit, /resolveQuoteReviewIssue/);
  assert.doesNotMatch(cockpit, /correctQuoteReviewLine/);
  assert.doesNotMatch(cockpit, /selectQuoteReviewSubstitute/);
  assert.doesNotMatch(cockpit, /dangerouslySetInnerHTML/);
  assert.doesNotMatch(cockpit, /localStorage/);
});
