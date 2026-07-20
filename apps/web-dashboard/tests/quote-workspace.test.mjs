import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");
const workspace = readFileSync(join(root, "components", "quote-workspace.tsx"), "utf8");
const idempotencyHelper = readFileSync(join(root, "lib", "security-idempotency.ts"), "utf8");
const quoteReviewApi = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const quoteReviewCockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");

test("quote approval API client exposes Stage 12B endpoints", () => {
  assert.match(apiClient, /getQuoteApprovalState/);
  assert.match(apiClient, /approveQuote/);
  assert.match(apiClient, /rejectQuote/);
  assert.match(apiClient, /requestQuoteChanges/);
  assert.match(apiClient, /convertQuoteToInternalOrder/);
  assert.match(apiClient, /\/api\/v1\/quotes\/\$\{quoteId\}\/approval-state/);
  assert.match(apiClient, /\/approve/);
  assert.match(apiClient, /\/reject/);
  assert.match(apiClient, /\/request-changes/);
  assert.match(apiClient, /\/convert-to-internal-order/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("quote workspace renders approval panel and guarded actions when mutation offered", () => {
  assert.match(workspace, /Approval required/);
  assert.match(workspace, /Approval reasons/);
  assert.match(workspace, /Blocking issues/);
  assert.match(workspace, /canPerformQuoteAction/);
  assert.match(workspace, /Approve/);
  assert.match(workspace, /Reject/);
  assert.match(workspace, /Request changes/);
  assert.match(workspace, /Approval decision reason/);
  assert.match(workspace, /Reason\/comment is required/);
  assert.match(workspace, /some\(\(issue\) => issue\.blocking\)/);
});

test("quote workspace shows conversion only for approved quote and external writes disabled", () => {
  assert.match(workspace, /result\.status === "APPROVED"/);
  assert.match(workspace, /Convert to internal order/);
  assert.match(workspace, /External ERP write: disabled \/ not executed/);
  assert.match(workspace, /External execution/);
  assert.match(workspace, /External ERP write was not executed/);
});

test("quote workspace does not use Date.now() for idempotency keys", () => {
  assert.doesNotMatch(workspace, /idempotencyKey:.*Date\.now\(\)/);
  assert.doesNotMatch(workspace, /idempotencyKey:\s*`.*Date\.now\(\)/);
  assert.doesNotMatch(workspace, /Math\.random\(\)/);
  assert.doesNotMatch(idempotencyHelper, /Math\.random\(\)/);
});

test("quote workspace uses intent-bound idempotency keys", () => {
  assert.match(workspace, /idempotencyKeyForCreateDraftFromRfq/);
  assert.match(workspace, /idempotencyKeyForQuoteApprovalAction/);
  assert.match(workspace, /useOperatorAction/);
});

test("quote workspace guards duplicate submission via operator action runtime", () => {
  assert.match(workspace, /useOperatorAction/);
  assert.match(workspace, /disabled=\{busy \|\| actionDisabled\}/);
  assert.match(workspace, /\{busy \? "Submitting\.\.\." : "Create Draft Quote"\}/);
});

test("quote transaction API sends idempotency key as header and strips it from JSON body", () => {
  assert.match(apiClient, /"Idempotency-Key": idempotencyKey/);
  assert.match(apiClient, /const \{ idempotencyKey, \.\.\.body \} = payload/);
  assert.match(apiClient, /body: JSON\.stringify\(body\)/);
});

test("quote review mutations use stable ref keys and shared operator action runtime", () => {
  assert.match(
    quoteReviewCockpit,
    /const mutationKeysRef = useRef<Map<string, string>>\(new Map\(\)\)/
  );

  assert.match(
    quoteReviewCockpit,
    /useOperatorAction<QuoteReviewCommandResult>/
  );

  assert.match(
    quoteReviewCockpit,
    /generateIdempotencyKey\(\)/
  );

  assert.match(
    quoteReviewCockpit,
    /mutationKeysRef\.current\.delete\(actionKey\)/
  );

  assert.match(
    quoteReviewCockpit,
    /resolve-issue-\$\{issue\.id\}/
  );

  assert.match(
    quoteReviewCockpit,
    /substitute-select-\$\{candidate\.lineId\}-\$\{candidate\.productId\}/
  );

  assert.match(
    quoteReviewCockpit,
    /mapOperatorActionError/
  );

  assert.doesNotMatch(
    quoteReviewCockpit,
    /const mutationInFlightRef = useRef\(false\)/
  );

  assert.doesNotMatch(
    quoteReviewCockpit,
    /if \(mutationInFlightRef\.current\) return/
  );
});

test("quote review API sends idempotency key as header and strips it from command payload", () => {
  assert.match(quoteReviewApi, /idempotencyKey\?: string/);
  assert.match(quoteReviewApi, /const \{ idempotencyKey, \.\.\.body \} = payload/);
  assert.match(quoteReviewApi, /"Idempotency-Key": idempotencyKey/);
  assert.match(quoteReviewApi, /body: JSON\.stringify\(body\)/);
});
