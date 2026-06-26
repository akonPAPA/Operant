import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-47B — operator fulfillment timeline frontend surface. Source-inspection style, consistent
// with tests/order-journey.test.mjs: assert the read contract, operator-safety boundary, required
// UI states, and that no backend authority fields are introduced into frontend state.

const root = process.cwd();
const apiClientPath = join(root, "lib", "order-journey-api.ts");
const componentPath = join(root, "components", "operator-fulfillment-timeline.tsx");
const detailPath = join(root, "components", "order-journey-detail.tsx");

const apiClient = readFileSync(apiClientPath, "utf8");
const component = readFileSync(componentPath, "utf8");
const detail = readFileSync(detailPath, "utf8");

test("component and api client method exist", () => {
  assert.equal(existsSync(componentPath), true);
  assert.match(apiClient, /getOperatorFulfillmentTimeline/);
  assert.match(component, /OperatorFulfillmentTimeline/);
});

test("api client reads the OP-CAP-47A endpoint, GET-only and tenant/permission scoped", () => {
  assert.match(apiClient, /\/operator-timeline/);
  // The operator-timeline helper goes through the shared read() (GET + ANALYTICS_READ); it must not
  // introduce a mutation for this surface.
  const idx = apiClient.indexOf("getOperatorFulfillmentTimeline");
  const block = apiClient.slice(idx, idx + 400);
  assert.match(block, /read<OperatorFulfillmentTimeline>/);
  assert.doesNotMatch(block, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
});

test("typed contract carries only operator-safe fields", () => {
  // Required safe fields are declared.
  for (const field of [
    "journeyId",
    "currentStage",
    "currentStatus",
    "riskLevel",
    "blocked",
    "signalCount",
    "latestSignalReceivedAt",
    "returnRequested",
    "timeline",
    "sequence",
    "evidenceLevel",
    "customerVisible"
  ]) {
    assert.match(apiClient, new RegExp(field), `expected contract field ${field}`);
  }
});

test("no raw/internal/security fields in the timeline type or component", () => {
  // The OperatorTimelineEntry / OperatorFulfillmentTimeline types and the component must never
  // declare or render raw payload refs, source refs, idempotency keys, tenant/actor/audit ids, etc.
  // Strip line comments first — the safety comments deliberately *name* these absent fields.
  const stripComments = (src) =>
    src
      .split("\n")
      .filter((line) => !line.trim().startsWith("//"))
      .join("\n");

  const typeStart = apiClient.indexOf("OperatorTimelineEntry");
  const typeEnd = apiClient.indexOf("JourneyProjectionHealth");
  const typeBlock = stripComments(apiClient.slice(typeStart, typeEnd));
  const componentCode = stripComments(component);
  for (const banned of [
    /payloadRef/i,
    /sourceRef/i,
    /idempotency/i,
    /tenantId/i,
    /actorId/i,
    /auditId/i,
    /\bconfidence\b/i,
    /sourceId/i,
    /customerAccountId/i,
    /storageRef/i
  ]) {
    assert.doesNotMatch(typeBlock, banned, `banned field leaked into contract: ${banned}`);
    assert.doesNotMatch(componentCode, banned, `banned field rendered in component: ${banned}`);
  }
});

test("component renders the required summary card fields", () => {
  assert.match(component, /Current status/);
  assert.match(component, /Current stage/);
  assert.match(component, /Risk level/);
  assert.match(component, /Signals/);
  assert.match(component, /Latest signal received/);
  assert.match(component, /BlockedBadge/);
});

test("component renders the ordered timeline list with safe per-entry fields", () => {
  assert.match(component, /Signal timeline/);
  assert.match(component, /aria-label="Operator fulfillment timeline"/);
  // Ordered strictly by backend sequence, not re-derived from timestamps.
  assert.match(component, /sort\(\(a, b\) => a\.sequence - b\.sequence\)/);
  assert.match(component, /entry\.label/);
  assert.match(component, /entry\.sourceType/);
  assert.match(component, /EvidenceBadge/);
  assert.match(component, /entry\.receivedAt/);
  assert.match(component, /entry\.processedAt/);
});

test("return requested is surfaced as an attention/warning state", () => {
  assert.match(component, /returnRequested/);
  assert.match(component, /Return requested/);
  assert.match(component, /return has been requested/i);
});

test("loading, empty and error states are present and safe", () => {
  // Loading skeleton
  assert.match(component, /OperatorFulfillmentTimelineSkeleton/);
  assert.match(component, /Loading fulfillment timeline/);
  // Empty state
  assert.match(component, /No fulfillment signals recorded/);
  // Error state — safe message, no raw backend body / stack trace surfaced
  assert.match(component, /unavailable right now/i);
  assert.doesNotMatch(component, /error\.stack|response\.text\(\)|console\.(log|error)/i);
});

test("surface is read-only — no mutation, form, or external-write controls", () => {
  assert.doesNotMatch(component, /<form|onSubmit|method:\s*"POST"|executeConnector|approve|sendMessage/i);
  // No dev-tool input fields (tenant/actor/source/internal id inputs).
  assert.doesNotMatch(component, /<input|<textarea|<select/i);
  // No raw JSON dump as the primary UI.
  assert.doesNotMatch(component, /JSON\.stringify/);
});

test("no backend authority fields are written into frontend state", () => {
  // The surface never submits tenant/actor/status/risk/milestone/signal/approval/execution state.
  assert.doesNotMatch(component, /setStatus|setRisk|setTenant|setApproval|body:\s*JSON\.stringify/i);
});

test("integrated into the operator journey detail page behind a Suspense loading boundary", () => {
  assert.match(detail, /OperatorFulfillmentTimeline/);
  assert.match(detail, /Suspense/);
  assert.match(detail, /OperatorFulfillmentTimelineSkeleton/);
});

test("public customer tracking surface is untouched by this component", () => {
  // The operator surface must not reach into the public tracking page/component.
  assert.doesNotMatch(component, /public-order-tracking|public\/order-tracking|customer-safe/i);
});
