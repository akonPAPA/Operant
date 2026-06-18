import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

// OP-CAP-31: prove the quote-transaction frontend contract sends business intent only. Tenant goes
// through the X-Tenant-Id header; actor/role are backend-owned and must never be in the JSON body.
const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");

function typeBlock(source, typeName) {
  const match = source.match(new RegExp(`export type ${typeName} = \\{([\\s\\S]*?)\\n\\};`));
  assert.ok(match, `Missing ${typeName}`);
  return match[1];
}

test("RFQ payload type carries business intent only (no actor/role)", () => {
  const block = typeBlock(apiClient, "CreateDraftQuoteFromRfqPayload");
  assert.doesNotMatch(block, /\b(actorId|actorRole|actorType)\??:/);
  assert.match(block, /customerExternalRef/);
  assert.match(block, /requestedItems/);
});

test("approval decision payload type carries business intent only (no actor/role)", () => {
  const block = typeBlock(apiClient, "QuoteApprovalDecisionPayload");
  assert.doesNotMatch(block, /\b(actorId|actorRole|actorType)\??:/);
  assert.match(block, /approvalRequestId\??:/);
  assert.match(block, /reason\??:/);
});

test("channel-to-quote payload type carries business intent only (no actor)", () => {
  const block = typeBlock(apiClient, "ChannelToQuotePayload");
  assert.doesNotMatch(block, /\b(actorId|actorType|actorRole|tenantId)\??:/);
  assert.match(block, /requestedCustomerAccountId\??:/);
});

test("RFQ request strips tenantId from the JSON body (header only)", () => {
  assert.match(apiClient, /const \{ idempotencyKey, tenantId, \.\.\.body \} = payload;/);
  assert.match(apiClient, /"X-Tenant-Id": tenantId/);
});

test("approval request body contains only business intent, never tenant/actor", () => {
  assert.match(
    apiClient,
    /const body = payload\s*\?\s*\{ approvalRequestId: payload\.approvalRequestId, reason: payload\.reason, comment: payload\.comment \}/
  );
  assert.doesNotMatch(apiClient, /JSON\.stringify\(\{[^}]*tenantId/);
});

test("source summary contract exposes no raw internal identifiers", () => {
  const block = typeBlock(apiClient, "QuoteSourceContext");
  assert.doesNotMatch(block, /\b(sourceId|conversionAttemptId|triggeredBy|createdByType)\??:/);
  assert.match(block, /candidateLineCount/);
  assert.match(block, /reviewRequired/);
});

test("non-2xx responses map to safe messages, never raw backend body dumps", () => {
  assert.match(apiClient, /function safeErrorMessage\(status: number\)/);
  assert.match(apiClient, /throw new Error\(safeErrorMessage\(response\.status\)\)/);
  // No code path rethrows the raw response text/body as the error message.
  assert.doesNotMatch(apiClient, /throw new Error\(message \|\|/);
});
