import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-transaction-api.ts"), "utf8");
const panel = readFileSync(join(root, "components", "channel-quote-conversion-panel.tsx"), "utf8");
const runtime = readFileSync(join(root, "lib", "operator-action-runtime.ts"), "utf8");
const sourcePanel = readFileSync(join(root, "components", "quote-source-context-panel.tsx"), "utf8");
const messagePage = readFileSync(join(root, "app", "(dashboard)", "messages", "[id]", "page.tsx"), "utf8");
const documentPage = readFileSync(join(root, "app", "(dashboard)", "documents", "[id]", "page.tsx"), "utf8");
const quotePage = readFileSync(join(root, "app", "(dashboard)", "quotes", "[id]", "page.tsx"), "utf8");

test("channel-to-quote API client exposes conversion and source context endpoints", () => {
  assert.match(apiClient, /createQuoteFromChannelMessage/);
  assert.match(apiClient, /createQuoteFromInboundDocument/);
  assert.match(apiClient, /getQuoteSourceContext/);
  assert.match(apiClient, /\/api\/v1\/quote-transactions\/from-channel-message\/\$\{messageId\}/);
  assert.match(apiClient, /\/api\/v1\/quote-transactions\/from-inbound-document\/\$\{documentId\}/);
  assert.match(apiClient, /\/api\/v1\/quotes\/\$\{quoteId\}\/source-context/);
  assert.match(apiClient, /X-Tenant-Id/);
});

test("source detail pages render create quote draft wiring", () => {
  assert.match(messagePage, /ChannelQuoteConversionPanel/);
  assert.match(messagePage, /sourceType="CHANNEL_MESSAGE"/);
  assert.match(documentPage, /ChannelQuoteConversionPanel/);
  assert.match(documentPage, /sourceType="INBOUND_DOCUMENT"/);
  assert.match(panel, /Prepare Quote/);
  assert.match(panel, /Create Quote Draft/);
  assert.match(panel, /Dry run preview/);
  assert.match(panel, /Conversion rejected by backend validation/);
  assert.match(panel, /Review required before draft quote creation/);
  assert.match(panel, /validationIssues/);
});

test("quote detail page renders source summary panel", () => {
  assert.match(quotePage, /QuoteSourceContextPanel/);
  assert.match(sourcePanel, /Request Source Summary/);
  assert.match(sourcePanel, /Load Source Summary/);
  assert.match(sourcePanel, /sourceExternalRef/);
});

// OP-CAP-31: the source summary panel must not expose an editable Tenant ID or raw internal
// identifiers (sourceId, conversionAttemptId, triggeredBy/internal actor).
test("source summary panel does not expose editable tenant id or raw internal identifiers", () => {
  assert.doesNotMatch(sourcePanel, /<span>Tenant ID<\/span>/);
  assert.doesNotMatch(sourcePanel, /onChange=\{\(event\) => setTenantId/);
  assert.doesNotMatch(sourcePanel, /context\.sourceId/);
  assert.doesNotMatch(sourcePanel, /context\.conversionAttemptId/);
  assert.doesNotMatch(sourcePanel, /context\.triggeredBy/);
  assert.doesNotMatch(sourcePanel, /context\.createdByType/);
});

// OP-CAP-33: the conversion panel must not offer an editable Tenant ID. Tenant is resolved from
// env/config and the backend TenantContext is the authority boundary.
test("conversion panel does not expose editable tenant id", () => {
  assert.doesNotMatch(panel, /<span>Tenant ID<\/span>/);
  assert.doesNotMatch(panel, /onChange=\{\(event\) => setTenantId/);
  assert.doesNotMatch(panel, /setTenantId/);
  assert.match(panel, /const tenantId = process\.env\.NEXT_PUBLIC_DEMO_TENANT_ID/);
  assert.match(panel, /tenant-context-info/);
});

// OP-CAP-32: the conversion result panel must not render the (intentionally @JsonIgnore'd)
// conversion attempt id or any raw source/audit identifier.
test("conversion result panel does not render hidden internal identifiers", () => {
  assert.doesNotMatch(panel, /result\.conversionAttemptId/);
  assert.doesNotMatch(panel, /result\.sourceId/);
  assert.doesNotMatch(panel, /result\.auditEventIds/);
});

// ── OP-CAP-34: Operator Action Runtime Foundation ────────────────────────────

// Runtime exists and exports the required symbols.
test("operator-action-runtime module exports hook, idempotency helper, error mapper, and result type", () => {
  assert.match(runtime, /export function useOperatorAction/);
  assert.match(runtime, /export function createOperatorIdempotencyKey/);
  assert.match(runtime, /export function mapOperatorActionError/);
  assert.match(runtime, /export type OperatorActionResult/);
  assert.match(runtime, /export type OperatorActionErrorCode/);
});

// mapOperatorActionError returns safe, status-based messages.
test("mapOperatorActionError covers all required status codes with safe messages", () => {
  assert.match(runtime, /status === 400 \|\| status === 422/);
  assert.match(runtime, /status === 401 \|\| status === 403/);
  assert.match(runtime, /status === 404/);
  assert.match(runtime, /status === 409/);
  assert.match(runtime, /status === 429/);
  // Must not render raw stack traces or internal identifier patterns.
  assert.doesNotMatch(runtime, /\bstackTrace\b/);
  assert.doesNotMatch(runtime, /\brawError\b/);
});

// The duplicate-click guard must be released after the action completes, otherwise the
// button would be permanently disabled after the first interaction.
test("useOperatorAction releases the in-flight guard in a finally block", () => {
  assert.match(runtime, /inFlightRef\.current = true;/);
  // A finally block must reset the guard so subsequent attempts are allowed.
  assert.match(runtime, /\}\s*finally\s*\{[\s\S]*inFlightRef\.current = false;[\s\S]*\}/);
});

// createOperatorIdempotencyKey must be deterministic (no randomness) so identical
// operations produce identical keys and the backend can dedup retries.
test("createOperatorIdempotencyKey is deterministic and derived only from actionName/resourceHandle", () => {
  const keyFn = runtime.match(/export function createOperatorIdempotencyKey[\s\S]*?\n\}/);
  assert.ok(keyFn, "createOperatorIdempotencyKey function body not found");
  // The key is built only from actionName and optional resourceHandle.
  assert.match(keyFn[0], /\$\{actionName\}-\$\{resourceHandle\}/);
  assert.match(keyFn[0], /return actionName;/);
  // No randomness: must not call a UUID/random generator inside the key.
  assert.doesNotMatch(keyFn[0], /generateIdempotencyKey/);
  assert.doesNotMatch(keyFn[0], /randomUUID/);
  assert.doesNotMatch(keyFn[0], /Math\.random/);
  // No backend-owned authority fields interpolated into the key template.
  assert.doesNotMatch(keyFn[0], /\$\{tenantId\}/);
  assert.doesNotMatch(keyFn[0], /\$\{actorId\}/);
});

// The runtime must not import a random key generator for idempotency keys.
test("operator-action-runtime does not source idempotency keys from a random generator", () => {
  assert.doesNotMatch(runtime, /import \{ generateIdempotencyKey \}/);
});

// The conversion panel imports and uses the shared operator-action runtime.
test("conversion panel imports useOperatorAction, createOperatorIdempotencyKey, OperatorActionResult", () => {
  assert.match(panel, /import \{[\s\S]*createOperatorIdempotencyKey[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
  assert.match(panel, /import \{[\s\S]*useOperatorAction[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
  assert.match(panel, /import \{[\s\S]*OperatorActionResult[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
});

// Idempotency key is generated via the shared helper, not ad-hoc string construction.
test("conversion panel uses createOperatorIdempotencyKey, not ad-hoc idempotency key", () => {
  assert.match(panel, /createOperatorIdempotencyKey\(/);
  // The old ad-hoc pattern must be gone.
  assert.doesNotMatch(panel, /\$\{sourceType\.toLowerCase\(\)\}-\$\{sourceId\}-\$\{dryRun \? "preview" : "draft"\}/);
});

// Button uses the hook's disabled state, not a manual loading boolean.
test("conversion panel button uses disabled from useOperatorAction hook", () => {
  assert.match(panel, /disabled=\{disabled\}/);
  assert.match(panel, /pending \? "Preparing\.\.\."/);
  // The old manual loading state must be gone.
  assert.doesNotMatch(panel, /\bsetLoading\b/);
});

// Payload in the panel carries business intent only — no tenant/actor/authority in body.
test("conversion panel submit payload carries business intent only", () => {
  assert.doesNotMatch(panel, /payload\["tenantId"\]/);
  assert.doesNotMatch(panel, /payload\["actorId"\]/);
  assert.doesNotMatch(panel, /payload\["sourceId"\]/);
  assert.doesNotMatch(panel, /payload\["status"\]/);
  assert.doesNotMatch(panel, /payload\["risk"\]/);
  assert.doesNotMatch(panel, /payload\["approval"\]/);
  assert.doesNotMatch(panel, /payload\["approvalStatus"\]/);
  assert.doesNotMatch(panel, /payload\["executionStatus"\]/);
  assert.doesNotMatch(panel, /payload\["conversionAttemptId"\]/);
  assert.doesNotMatch(panel, /payload\["auditEventIds"\]/);
  // Business-intent fields must be present.
  assert.match(panel, /requestedCustomerAccountId/);
  assert.match(panel, /dryRun/);
  assert.match(panel, /idempotencyKey/);
});
