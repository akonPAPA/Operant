import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "quote-review-api.ts"), "utf8");
const coreApiClient = readFileSync(join(root, "lib", "core-api-client.ts"), "utf8");
const cockpit = readFileSync(join(root, "components", "quote-review-cockpit.tsx"), "utf8");
const runtime = readFileSync(join(root, "lib", "operator-action-runtime.ts"), "utf8");
const idempotency = readFileSync(join(root, "lib", "security-idempotency.ts"), "utf8");
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
  assert.match(apiClient, /demoScopeHeaders/);
  assert.match(coreApiClient, /X-Tenant-Id/);
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
  assert.match(cockpit, /encodeURIComponent\(quoteId\)/);
  assert.match(cockpit, /UUID_RE\.test\(quoteId\)/);
  assert.match(cockpit, /reviewHref \? <a className="button secondary-button" href=\{reviewHref\}>Open Review<\/a> : <span className="muted-copy">Unavailable<\/span>/);
  assert.doesNotMatch(cockpit, /URLSearchParams\(\{ tenantId \}\)/);
  assert.doesNotMatch(cockpit, /href=\{`\/quote-review\/\$\{row\.quoteId\}\?tenantId=\$\{tenantId\}`\}/);
  assert.doesNotMatch(cockpit, /dangerouslySetInnerHTML|innerHTML|outerHTML|insertAdjacentHTML|DOMParser|document\.write/);
});

// ── OP-CAP-35: Operator Action Runtime Integration ────────────────────────────

// Cockpit imports the shared operator-action runtime.
test("quote review cockpit imports operator-action runtime", () => {
  assert.match(cockpit, /import \{[\s\S]*mapOperatorActionError[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
  assert.match(cockpit, /import \{[\s\S]*useOperatorAction[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
});

// The local mutationInFlight direct state management is replaced by the hook.
test("quote review cockpit uses useOperatorAction and removes local mutationInFlight state", () => {
  assert.match(cockpit, /const \{ execute, pending, disabled \} = useOperatorAction/);
  // The old local mutation-in-flight boolean must be gone.
  assert.doesNotMatch(cockpit, /const \[mutationInFlight, setMutationInFlight\] = useState/);
  assert.doesNotMatch(cockpit, /const mutationInFlightRef = useRef\(false\)/);
});

// Buttons use disabled from the hook, not local mutationInFlight.
test("quote review mutation buttons use disabled from useOperatorAction hook", () => {
  assert.match(cockpit, /disabled=\{issue\.status !== "OPEN" \|\| disabled\}/);
  assert.match(cockpit, /disabled=\{disabled\}/);
  // Old mutationInFlight-based disabled must be gone.
  assert.doesNotMatch(cockpit, /\|\| mutationInFlight\b/);
});

// Duplicate-click guard is provided by the hook (ref inside execute).
test("quote review cockpit duplicate-click guard is provided by the shared runtime", () => {
  // The hook's execute has a ref-based in-flight guard. The cockpit no longer
  // maintains its own raw in-flight ref for this purpose.
  assert.match(runtime, /inFlightRef\.current/);
});

// Idempotency keys still use generateIdempotencyKey (UUID-per-actionKey),
// preserving the existing pattern. The deterministic runtime helper is NOT
// used for quote review because UUID-per-action is equally safe and
// backward-compatible.
test("quote review idempotency key uses generateIdempotencyKey, not deterministic runtime helper", () => {
  assert.match(cockpit, /import \{ generateIdempotencyKey \} from "@\/lib\/security-idempotency"/);
  // Does NOT import or use the deterministic helper from the runtime.
  assert.doesNotMatch(cockpit, /import \{[\s\S]*createOperatorIdempotencyKey[\s\S]*\} from "@\/lib\/operator-action-runtime"/);
  assert.doesNotMatch(cockpit, /createOperatorIdempotencyKey\(/);
  // Still uses generateIdempotencyKey().
  assert.match(cockpit, /generateIdempotencyKey\(\)/);
});

// Request payloads are business-intent-only — no backend-owned authority fields.
test("quote review request payloads carry business intent only (no tenant/actor/authority)", () => {
  // resolveQuoteReviewIssue payload — reasonCode, note, idempotencyKey are business intent.
  assert.match(cockpit, /\{ reasonCode: reason, note, idempotencyKey \}/);
  // correctQuoteReviewLine payloads — business fields only.
  assert.match(cockpit, /\{ quantity: Number\(line\.quantity\) > 0 \? Number\(line\.quantity\) : 1, reasonCode: reason, note, idempotencyKey \}/);
  assert.match(cockpit, /\{ uom: "EA", reasonCode: reason, note, idempotencyKey \}/);
  assert.match(cockpit, /\{ manualFollowUp: true, reasonCode: "MANUAL_FOLLOW_UP", note, idempotencyKey \}/);
  // selectQuoteReviewSubstitute payload.
  assert.match(cockpit, /\{ substituteProductId: candidate\.productId, reasonCode: reason, note, idempotencyKey \}/);
  // rejectQuoteReviewSubstitute payload.
  assert.match(cockpit, /\{ substituteProductId: candidate\.productId, reasonCode: reason, note, idempotencyKey \}/);
  // No authority fields.
  assert.doesNotMatch(cockpit, /payload\["tenantId"\]/);
  assert.doesNotMatch(cockpit, /payload\["actorId"\]/);
  assert.doesNotMatch(cockpit, /payload\["sourceId"\]/);
  assert.doesNotMatch(cockpit, /payload\["status"\]/);
  assert.doesNotMatch(cockpit, /payload\["risk"\]/);
  assert.doesNotMatch(cockpit, /payload\["approval"\]/);
  assert.doesNotMatch(cockpit, /payload\["margin"\]/);
  assert.doesNotMatch(cockpit, /payload\["stock"\]/);
  assert.doesNotMatch(cockpit, /payload\["approvalStatus"\]/);
  assert.doesNotMatch(cockpit, /payload\["executionStatus"\]/);
  assert.doesNotMatch(cockpit, /payload\["conversionAttemptId"\]/);
  assert.doesNotMatch(cockpit, /payload\["auditEventIds"\]/);
});

// ── OP-CAP-35 idempotency regression: cached key lifecycle ────────────────────

// A successful action must clear the cached idempotency key so the next
// intentional click on the same actionKey gets a fresh key (backend does not
// treat it as a duplicate/replay).
test("quote review doAction clears cached idempotency key after a successful action", () => {
  assert.match(cockpit, /const result = await execute\(/);
  assert.match(cockpit, /if \(result\.ok\) \{\s*mutationKeysRef\.current\.delete\(actionKey\);\s*\}/);
});

// The key must be cleared only AFTER the action completes — the delete must
// appear after the awaited execute(...) call, never before it.
test("quote review doAction does not clear cached idempotency key before completion", () => {
  const executeIndex = cockpit.indexOf("const result = await execute(");
  const deleteIndex = cockpit.indexOf("mutationKeysRef.current.delete(actionKey)");
  assert.ok(executeIndex >= 0, "expected awaited execute call in doAction");
  assert.ok(deleteIndex >= 0, "expected cached key deletion in doAction");
  assert.ok(deleteIndex > executeIndex, "cached key must be deleted only after execute completes");
  // No unconditional delete (must be guarded by result.ok success).
  assert.doesNotMatch(cockpit, /mutationKeysRef\.current\.clear\(\)/);
});

// Error mapping uses mapOperatorActionError with HTTP status, not raw errors.
test("quote review doAction uses mapOperatorActionError for safe error mapping", () => {
  assert.match(cockpit, /mapOperatorActionError\(err\.status \?\? 500, err\.message\)/);
  // Must not render raw error stack traces or internal details.
  assert.doesNotMatch(cockpit, /\bconsole\.error\b/);
  assert.doesNotMatch(cockpit, /\bstackTrace\b/);
  assert.doesNotMatch(cockpit, /\brawError\b/);
});

// The API client attaches HTTP status to errors so mapOperatorActionError can
// produce status-specific safe messages.
test("quote review API client attaches HTTP status to thrown command errors", () => {
  assert.match(apiClient, /Object\.assign\(\s*new Error\(coreApiStatusMessage\(response\.status\)\)/);
  assert.match(apiClient, /\{ status: response\.status \}/);
});

// ── OP-CAP-36: Quote Draft Assembly Workflow ──────────────────────────────────

test("quote review API client exposes assemble-draft command with business-intent payload", () => {
  assert.match(apiClient, /export function assembleQuoteDraft/);
  assert.match(apiClient, /\/api\/v1\/quote-review\/\$\{quoteId\}\/assemble-draft/);
  // Reuses the shared command helper (header tenant, status-attached errors).
  assert.match(apiClient, /assembleQuoteDraft[\s\S]*requestQuoteReview<QuoteDraftSummary>/);
  // Summary type exposes backend-owned safe fields only — no authority/internal IDs.
  assert.match(apiClient, /export type QuoteDraftSummary = \{/);
  assert.doesNotMatch(apiClient, /QuoteDraftSummary = \{[\s\S]*tenantId[\s\S]*\};/);
  assert.doesNotMatch(apiClient, /QuoteDraftSummary = \{[\s\S]*actorId[\s\S]*\};/);
  assert.doesNotMatch(apiClient, /QuoteDraftSummary = \{[\s\S]*auditEventIds[\s\S]*\};/);
  assert.doesNotMatch(apiClient, /QuoteDraftSummary = \{[\s\S]*sourceId[\s\S]*\};/);
});

test("quote review cockpit wires assemble-draft through the operator-action runtime", () => {
  assert.match(cockpit, /Assemble Draft Quote/);
  assert.match(cockpit, /Draft Quote Summary/);
  // Assemble goes through the shared doAction (idempotency-key lifecycle + execute).
  assert.match(cockpit, /async function assembleDraft\(\)/);
  assert.match(cockpit, /doAction\(\s*"assemble-draft"/);
  // Payload is business intent only.
  assert.match(cockpit, /assembleQuoteDraft\(quoteId, \{ reasonCode: reason, note, idempotencyKey \}\)/);
});

test("quote review assemble button is gated and uses hook pending/disabled state", () => {
  assert.match(cockpit, /const hasOpenBlockingIssue =/);
  assert.match(cockpit, /disabled=\{hasOpenBlockingIssue \|\| disabled\}/);
  assert.match(cockpit, /onClick=\{assembleDraft\}/);
  assert.match(cockpit, /\{pending \? "Working\.\.\." : "Assemble Draft Quote"\}/);
});

test("quote review draft summary renders only safe backend-owned fields", () => {
  assert.match(cockpit, /draftSummary\.draftStatus/);
  assert.match(cockpit, /draftSummary\.approvalRequired/);
  assert.match(cockpit, /draftSummary\.riskLevel/);
  assert.match(cockpit, /draftSummary\.externalExecution/);
  // Must not render backend-owned authority/internal identifiers in the summary.
  assert.doesNotMatch(cockpit, /draftSummary\.tenantId/);
  assert.doesNotMatch(cockpit, /draftSummary\.actorId/);
  assert.doesNotMatch(cockpit, /draftSummary\.createdBy/);
  assert.doesNotMatch(cockpit, /draftSummary\.auditEventIds/);
  assert.doesNotMatch(cockpit, /draftSummary\.sourceId/);
});

// UI renders safe messages only — never raw backend body, stack traces, or
// internal identifiers.
test("quote review UI renders safe messages only, never raw internals", () => {
  // messageKind determines "error" or "done" class — safe, not content-based heuristics.
  assert.match(cockpit, /const \[messageKind, setMessageKind\] = useState/);
  assert.match(cockpit, /messageKind === "error" \? "form-message error" : "form-message done"/);
  // Must not render internal identifiers.
  assert.doesNotMatch(cockpit, /\bconversionAttemptId\b/);
  assert.doesNotMatch(cockpit, /\bauditEventIds\b/);
  // tenantId is not rendered as an editable value.
  assert.doesNotMatch(cockpit, /onChange=\{\(event\) => setTenantId/);
});
