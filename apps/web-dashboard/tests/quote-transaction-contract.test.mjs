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

test("channel-to-quote payload type carries business intent only (no actor, no tenant)", () => {
  const block = typeBlock(apiClient, "ChannelToQuotePayload");
  assert.doesNotMatch(block, /\b(actorId|actorType|actorRole|tenantId|sourceId|status|risk|approval|conversionAttemptId|auditEventIds)\??:/);
  assert.match(block, /requestedCustomerAccountId\??:/);
});

test("channel-to-quote request sends tenant via X-Tenant-Id header only, never in body", () => {
  // The requestQuoteTransaction helper strips the idempotency key from the body and puts
  // the demo-resolved tenant in the X-Tenant-Id header — tenant is never serialized to the JSON body.
  assert.match(apiClient, /"X-Tenant-Id": requireDemoTenantId\(\)/);
  assert.match(apiClient, /const \{ idempotencyKey, \.\.\.body \} = payload;/);
  // ChannelToQuotePayload must not declare tenantId.
  const block = typeBlock(apiClient, "ChannelToQuotePayload");
  assert.doesNotMatch(block, /tenantId/);
});

test("RFQ request strips tenantId from the JSON body (header only)", () => {
  assert.match(apiClient, /const \{ idempotencyKey, \.\.\.body \} = payload;/);
  assert.match(apiClient, /"X-Tenant-Id": requireDemoTenantId\(\)/);
  assert.doesNotMatch(apiClient, /tenantId:\s*string/);
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

// OP-CAP-32: the backend withholds internal identifiers on the conversion response
// (conversionAttemptId is @JsonIgnore'd; sourceId/auditEventIds are not in the DTO), so the
// frontend response contract must not declare them either.
test("channel-to-quote response contract exposes no raw internal identifiers", () => {
  const block = typeBlock(apiClient, "ChannelToQuoteResponse");
  assert.doesNotMatch(block, /\b(sourceId|conversionAttemptId|auditEventIds)\??:/);
  assert.match(block, /status:/);
  assert.match(block, /reviewRequired/);
});

// Wave 01J: the Stage12A approval response contract must not declare internal audit/actor/execution
// internals. External execution is surfaced as a safe boolean only.
test("approval state/command response contracts expose no audit/actor/internal-execution fields", () => {
  for (const typeName of ["QuoteApprovalState", "QuoteApprovalCommandResponse", "QuoteTransactionResponse"]) {
    const block = typeBlock(apiClient, typeName);
    assert.doesNotMatch(block, /\b(auditCorrelationId|decidedBy|externalExecutionStatus|actorId|actorRole)\??:/, typeName);
  }
  const state = typeBlock(apiClient, "QuoteApprovalState");
  assert.match(state, /externalExecutionEnabled:\s*boolean/);
  const command = typeBlock(apiClient, "QuoteApprovalCommandResponse");
  assert.match(command, /externalExecutionEnabled:\s*boolean/);
});

test("non-2xx responses map to safe messages, never raw backend body dumps", () => {
  assert.match(apiClient, /function safeErrorMessage\(status: number\)/);
  assert.match(apiClient, /throw new BoundedUiError\(safeErrorMessage\(response\.status\)\)/);
  // No code path rethrows the raw response text/body as the error message.
  assert.doesNotMatch(apiClient, /throw new Error\(message \|\|/);
});
