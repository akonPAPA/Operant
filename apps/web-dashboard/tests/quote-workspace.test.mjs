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

test("quote workspace renders approval panel and guarded actions", () => {
  assert.match(workspace, /Approval required/);
  assert.match(workspace, /Approval reasons/);
  assert.match(workspace, /Blocking issues/);
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

test("quote workspace uses secure idempotency key helper", () => {
  assert.match(workspace, /import \{ generateIdempotencyKey \} from "@\/lib\/security-idempotency"/);
  assert.match(idempotencyHelper, /crypto\.randomUUID/);
  assert.match(idempotencyHelper, /crypto\.getRandomValues/);
  assert.match(idempotencyHelper, /throw new Error\("Secure idempotency key generation is unavailable in this browser\."\)/);
  assert.match(workspace, /createDraftKeyRef\.current = generateIdempotencyKey\(\)/);
  assert.match(workspace, /approvalActionKeyRef\.current\.set\(actionKey, generateIdempotencyKey\(\)\)/);
});

test("quote workspace stores idempotency key in useRef for stable attempt state", () => {
  assert.match(workspace, /const loadingRef = useRef\(false\)/);
  assert.match(workspace, /const createDraftKeyRef = useRef<string \| null>\(null\)/);
  assert.match(workspace, /const approvalActionKeyRef = useRef<Map<string, string>>\(new Map\(\)\)/);
});

test("quote workspace clears draft key after successful creation", () => {
  assert.match(workspace, /createDraftKeyRef\.current = null/);
});

test("quote workspace guards against duplicate click during pending state", () => {
  assert.match(workspace, /if \(loadingRef\.current\) return/);
  assert.match(workspace, /loadingRef\.current = true/);
  assert.match(workspace, /loadingRef\.current = false/);
});

test("quote workspace locks buttons while request is pending", () => {
  assert.match(workspace, /disabled=\{loading\}/);
  assert.match(workspace, /\{loading \? "Submitting\.\.\." : "Create Draft Quote"\}/);
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
