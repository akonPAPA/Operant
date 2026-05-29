import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");
const workspace = readFileSync(join(root, "components", "quote-workspace.tsx"), "utf8");

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
