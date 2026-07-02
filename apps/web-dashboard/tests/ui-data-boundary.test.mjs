import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const aiWorkWorkspace = readFileSync(join(root, "components", "ai-work-assistant-workspace.tsx"), "utf8");
const conversionReviewCockpit = readFileSync(join(root, "components", "conversion-review-cockpit.tsx"), "utf8");
const intakeUploadForm = readFileSync(join(root, "components", "intake-upload-form.tsx"), "utf8");
const quoteReviewCockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");
const aiWorkApi = readFileSync(join(root, "lib", "ai-work-api.ts"), "utf8");
const quoteReviewApi = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");

const editableInternalIdInput =
  /<label>[\s\S]*?<span>\s*(Tenant ID|Actor ID|User ID|Source ID|Source id|Validation run id|Document ID|Connector ID|Correlation ID|Audit ID|Margin|Available stock|Model name|Prompt version|External write mode)\s*<\/span>[\s\S]*?<input/i;

function typeBlock(source, typeName) {
  const match = source.match(new RegExp(`export type ${typeName} = \\{([\\s\\S]*?)\\n\\};`));
  assert.ok(match, `Missing ${typeName}`);
  return match[1];
}

test("user-facing forms do not ask operators to type backend identifiers", () => {
  const surfaces = [
    ["AI Work Assistant", aiWorkWorkspace],
    ["Conversion Review", conversionReviewCockpit],
    ["Intake Upload", intakeUploadForm],
    ["Quote Review", quoteReviewCockpit]
  ];

  for (const [name, source] of surfaces) {
    assert.doesNotMatch(source, editableInternalIdInput, `${name} exposes an editable internal-id input`);
  }
});

test("conversion review UI does not render raw source identifiers or tenant ids", () => {
  assert.doesNotMatch(conversionReviewCockpit, /Tenant ID/i);
  assert.doesNotMatch(conversionReviewCockpit, /tenantId/i);
  assert.doesNotMatch(conversionReviewCockpit, /Source id/i);
  assert.doesNotMatch(conversionReviewCockpit, /detail\.sourceId/);
  assert.doesNotMatch(conversionReviewCockpit, /shortId\(attempt\.sourceId\)/);
});

test("AI advisory workspace does not require manually entered source ids", () => {
  assert.doesNotMatch(aiWorkWorkspace, /Source ID/i);
  assert.doesNotMatch(aiWorkWorkspace, /sourceId\.trim\(\)/);
  assert.doesNotMatch(aiWorkWorkspace, /Source ID is required/);
  assert.doesNotMatch(aiWorkWorkspace, /suggestion\.sourceId/);
});

test("frontend command payloads do not expose client-supplied authority or calculated fields", () => {
  const quoteCommand = typeBlock(quoteReviewApi, "QuoteReviewCommandPayload");
  assert.doesNotMatch(
    quoteCommand,
    /\b(tenantId|actorId|userId|actorRole|roleId|permissionId|membershipId|organizationId|sessionId|authSubject|status|reviewStatus|approvalStatus|validationStatus|riskLevel|margin|grossMargin|marginPercent|finalPrice|taxTotal|discountApproved|paymentConfirmed|settlementAmount|availableStock|reservedStock|inventoryFreshness|confidence|aiDecision|aiRiskScore|modelName|promptVersion|autoApprove|llmApproved|validationPassed|connectorCapability|externalWriteMode|erpWriteApproved|changeRequestApproved|providerCredentialRef)\??:/,
    "quote review command payload exposes authority, state, AI, connector, or calculated truth"
  );
  assert.match(quoteCommand, /\breasonCode\??:/);
  assert.match(quoteCommand, /\bnote\??:/);
  assert.match(quoteCommand, /\bquantity\??:/);
  assert.match(quoteCommand, /\buom\??:/);
  assert.match(quoteCommand, /\bproductId\??:/);
  assert.match(quoteCommand, /\bcustomerAccountId\??:/);
  assert.match(quoteCommand, /\bsubstituteProductId\??:/);

  const aiDecision = typeBlock(aiWorkApi, "AiWorkDecisionRequest");
  assert.doesNotMatch(
    aiDecision,
    /\b(decidedByUserId|actorId|userId|approvalStatus|riskLevel|autoApprove|llmApproved|erpWriteApproved|changeRequestApproved|externalWriteMode|confidence|aiDecision|aiRiskScore|modelName|promptVersion)\??:/,
    "AI Work decision payload exposes decision authority"
  );
  assert.match(aiDecision, /\breason\??:/);

  assert.doesNotMatch(aiWorkApi, /export function createAiWorkSuggestion/);
  assert.doesNotMatch(aiWorkApi, /export type CreateAiWorkSuggestionRequest/);
  assert.doesNotMatch(aiWorkApi, /\b(createdByUserId|decidedByUserId)\??:/);
  assert.doesNotMatch(quoteReviewApi, /auditTimeline: Array<\{[^}]*\b(id|entityId|actorId)\??:/);
  assert.doesNotMatch(quoteReviewApi, /auditTimeline: Array<\{[^}]*\bmetadata\??:/);
});
