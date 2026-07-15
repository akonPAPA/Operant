import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const aiWorkWorkspace = readFileSync(join(root, "components", "ai-work-assistant-workspace.tsx"), "utf8");
const aiWorkSchemaView = readFileSync(join(root, "components", "ai-work-schema-v1-view.tsx"), "utf8");
const conversionReviewCockpit = readFileSync(join(root, "components", "conversion-review-cockpit.tsx"), "utf8");
const intakeUploadForm = readFileSync(join(root, "components", "intake-upload-form.tsx"), "utf8");
const uploadPage = readFileSync(join(root, "app", "(dashboard)", "upload", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");
const quoteReviewCockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");
const aiWorkApi = readFileSync(join(root, "lib", "ai-work-api.ts"), "utf8");
const quoteReviewApi = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const commerceIntelligenceApi = readFileSync(
  join(root, "lib", "commerce-intelligence-api.ts"),
  "utf8"
);
const commerceIntelligenceView = readFileSync(
  join(root, "components", "commerce-intelligence-demo-flow.tsx"),
  "utf8"
);

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

test("production upload surface is capability-gated, not only visually hidden", () => {
  assert.match(navigation, /navigationGroupsForUploadCapability/);
  assert.match(navigation, /item\.href !== "\/upload"/);
  assert.match(uploadPage, /isUploadAvailable\(capability\)/);
  assert.match(uploadPage, /<IntakeUploadForm \/>/);
  assert.match(intakeUploadForm, /if \(!isUploadAvailable\(capability\)\)/);
  assert.match(intakeUploadForm, /uploadUnavailableMessage\(\)/);
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

test("Commerce Intelligence is a read-only public projection with no internal field rendering", () => {
  const recentFlow = typeBlock(commerceIntelligenceApi, "CommerceIntelligenceRecentFlow");
  assert.doesNotMatch(
    recentFlow,
    /\b(tenantId|actorId|reviewerUserId|createdByUserId|decidedByUserId|inboundChannelEventId|channelConnectionId|auditId|idempotencyKey|correlationId|rawPayload|payloadJson|prompt|token|secret|credential|stackTrace|quotaBucket|redisKey|jti|nonce|retryAfterSeconds)\??:/
  );
  assert.doesNotMatch(commerceIntelligenceApi, /body\s*:/);
  assert.doesNotMatch(commerceIntelligenceView, /JSON\.stringify|<pre/);
  assert.doesNotMatch(
    commerceIntelligenceView,
    /\.(tenantId|actorId|reviewerUserId|auditId|idempotencyKey|correlationId|rawPayload|payloadJson|prompt|token|secret|credential|stackTrace|quotaBucket|redisKey|jti|nonce)\b/
  );
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
  assert.doesNotMatch(aiWorkApi, /structuredPayloadJson/);
  assert.doesNotMatch(aiWorkApi, /evidenceRefsJson/);
  assert.doesNotMatch(aiWorkApi, /idempotencyKey/);
  const aiSuggestion = typeBlock(aiWorkApi, "AiWorkSuggestion");
  assert.match(aiSuggestion, /\bschemaVersion:/);
  assert.match(aiSuggestion, /\bsummary:/);
  assert.match(aiSuggestion, /\bnextActionCandidates:/);
  assert.match(aiSuggestion, /\bsafety:/);
  assert.doesNotMatch(
    aiSuggestion,
    /\b(strategyVersion|tenantId|actorId|rawPayload|payloadJson|prompt|apiKey|credential|auditEventId|stackTrace)\??:/
  );
  assert.match(aiWorkSchemaView, /AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION/);
  assert.doesNotMatch(
    aiWorkSchemaView,
    /generatedText|structuredPayloadJson|evidenceRefsJson|strategyVersion/
  );
  assert.doesNotMatch(aiWorkApi, /error instanceof Error \? error\.message/);
  assert.doesNotMatch(aiWorkApi, /\b(createdByUserId|decidedByUserId)\??:/);
  assert.doesNotMatch(quoteReviewApi, /auditTimeline: Array<\{[^}]*\b(id|entityId|actorId)\??:/);
  assert.doesNotMatch(quoteReviewApi, /auditTimeline: Array<\{[^}]*\bmetadata\??:/);
});

test("production direct upload page renders unavailable state before mounting the active form", () => {
  assert.match(uploadPage, /const capability = uploadCapability\(\)/);
  assert.match(uploadPage, /if \(!isUploadAvailable\(capability\)\) \{/);
  assert.match(uploadPage, /<h2>Not available<\/h2>/);
  assert.match(uploadPage, /uploadUnavailableMessage\(\)/);
  assert.match(uploadPage, /<IntakeUploadForm \/>/);
  assert.ok(uploadPage.indexOf("if (!isUploadAvailable(capability))") < uploadPage.indexOf("<IntakeUploadForm />"));
  assert.doesNotMatch(uploadPage.slice(0, uploadPage.indexOf("<IntakeUploadForm />")), /type="file"|onSubmit=|fetch\(/);
});

test("local demo upload form preserves multipart business-input request without browser authority fields", () => {
  assert.match(intakeUploadForm, /<form className="panel upload-form" onSubmit=\{submit\}>/);
  assert.match(intakeUploadForm, /<input name="file" type="file" accept=\{ACCEPTED_TYPES\} \/>/);
  assert.match(intakeUploadForm, /<button className="button" disabled=\{state\.status === "working"\} type="submit">/);
  assert.match(intakeUploadForm, /formData\.get\("file"\)/);
  assert.match(intakeUploadForm, /new FormData\(form\)/);
  assert.match(intakeUploadForm, /fetch\(`\$\{coreApiBaseUrl\(\)\}\/api\/v1\/intake\/documents\/upload`/);
  assert.match(intakeUploadForm, /method: "POST"/);
  assert.match(intakeUploadForm, /headers: demoScopeHeaders\(\)/);
  assert.match(intakeUploadForm, /body: formData/);
  assert.doesNotMatch(intakeUploadForm, /formData\.append\("tenantId"|formData\.append\("actorId"|formData\.append\("permissions"/);
  assert.doesNotMatch(intakeUploadForm, /X-OrderPilot-Permissions|X-OrderPilot-Actor-Id/);
});
