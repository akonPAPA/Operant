import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-46E — public customer tracking page. Source-inspection tests (the repo's established
// frontend test style) prove the public contract without a Next.js runtime: exact unauthenticated
// endpoint, no auth/tenant headers, read-only (no mutations), customer-safe rendering only, no
// token persistence/logging, and safe generic error states. Each assertion maps to a security/UX
// requirement; changing a contract here means updating the backend public DTO/route first.

const root = process.cwd();
const apiClientPath = join(root, "lib", "public-order-tracking-api.ts");
const viewPath = join(root, "components", "public-order-tracking.tsx");
const pagePath = join(root, "app", "public", "order-tracking", "[token]", "page.tsx");

const apiClient = readFileSync(apiClientPath, "utf8");
const view = readFileSync(viewPath, "utf8");

// Strip full-line `//` comments so the "no header" / "no internal field" assertions check actual
// code, not the documentation prose (which deliberately names what is withheld). All comments in
// these files are own-line, so this is sufficient and never touches strings or code.
function codeOnly(src) {
  return src
    .split("\n")
    .filter((line) => !/^\s*\/\//.test(line))
    .join("\n");
}
const apiClientCode = codeOnly(apiClient);
const viewCode = codeOnly(view);

// Internal/authority fields that must never be rendered or referenced as data on the public page,
// nor declared on the public response type. Mirrors the redacted backend PublicOrderTrackingView.
const FORBIDDEN_FIELDS = [
  "sourceRef",
  "sourceType",
  "actorType",
  "sortOrder",
  "customerVisible",
  "fulfillmentSignals",
  "riskLevel",
  "internalStatus",
  "rawPayloadRef",
  "connector",
  "tenantId",
  "journeyId",
  "tokenHash",
  "auditEventIds"
];

test("public route exists at app/public/order-tracking/[token]/page.tsx", () => {
  assert.equal(existsSync(pagePath), true);
});

test("public route lives outside the (dashboard) auth/shell group", () => {
  // The path segment is plain app/public/... — not inside the (dashboard) route group, so it
  // inherits no dashboard navigation, shell, or session expectation.
  assert.ok(!pagePath.includes("(dashboard)"));
  const page = readFileSync(pagePath, "utf8");
  assert.doesNotMatch(page, /DashboardShell/);
  // Token comes from the URL path param.
  assert.match(page, /params:\s*Promise<\{\s*token:\s*string\s*\}>/);
  assert.match(page, /PublicOrderTracking/);
});

test("API client calls the exact public endpoint with the path token", () => {
  assert.match(apiClient, /getPublicOrderTracking/);
  assert.match(apiClient, /\/api\/v1\/public\/order-tracking\/\$\{encodeURIComponent\(token\)\}/);
  assert.match(apiClient, /method:\s*"GET"/);
});

test("public fetch sends NO auth/tenant/permission headers", () => {
  // The token in the path is the sole credential — no dashboard scoping headers in actual code.
  assert.doesNotMatch(apiClientCode, /X-Tenant-Id/);
  assert.doesNotMatch(apiClientCode, /X-OrderPilot-Permissions/);
  assert.doesNotMatch(apiClientCode, /demoScopeHeaders/);
  assert.doesNotMatch(apiClientCode, /Authorization/);
});

test("public page performs no mutations (no POST/PUT/PATCH/DELETE)", () => {
  for (const src of [apiClient, view]) {
    assert.doesNotMatch(src, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
  }
  // No mutation/action wording on the customer page.
  assert.doesNotMatch(view, /<form|onSubmit|confirmDelivery|updateEta|updateMilestone|approve/i);
});

test("public response type declares only customer-safe fields", () => {
  assert.match(apiClient, /statusLabel:\s*string/);
  assert.match(apiClient, /milestones:\s*PublicTrackingMilestone\[\]/);
  assert.match(apiClient, /fulfillmentTrackingConnected:\s*boolean/);
  assert.match(apiClient, /generatedAt:\s*string/);
  assert.match(apiClient, /milestoneLabel:\s*string/);
  assert.match(apiClient, /milestoneState:\s*string/);
  assert.match(apiClient, /evidenceLevel:\s*string/);
  assert.match(apiClient, /occurredAt:\s*string\s*\|\s*null/);
  assert.match(apiClient, /estimatedAt:\s*string\s*\|\s*null/);
});

test("public page renders statusLabel and customer-safe milestones", () => {
  assert.match(view, /Order tracking/);
  assert.match(view, /statusLabel/);
  assert.match(view, /milestoneLabel/);
  assert.match(view, /MilestoneStateBadge/);
  assert.match(view, /EvidenceBadge/);
});

test("public page renders occurred/estimated timing safely without fabricating", () => {
  assert.match(view, /occurredAt/);
  assert.match(view, /estimatedAt/);
  assert.match(view, /Awaiting update/);
  assert.match(view, /Completed/);
  assert.match(view, /Estimated/);
});

test("public page shows safe invalid/expired and unavailable states", () => {
  assert.match(view, /invalid or has expired/i);
  assert.match(view, /temporarily unavailable/i);
});

test("API client maps 404 to invalid and never surfaces the raw backend body", () => {
  assert.match(apiClient, /response\.status === 404/);
  assert.match(apiClient, /kind:\s*"invalid"/);
  assert.match(apiClient, /kind:\s*"unavailable"/);
  // Backend error bodies are drained and discarded, never returned to the UI.
  assert.doesNotMatch(apiClient, /new Error\(text\)|message:\s*text/);
});

test("neither the API client nor the page render/reference internal fields", () => {
  for (const field of FORBIDDEN_FIELDS) {
    const pattern = new RegExp(`\\b${field}\\b`);
    assert.doesNotMatch(viewCode, pattern, `public page must not reference ${field}`);
    assert.doesNotMatch(apiClientCode, pattern, `public API client must not reference ${field}`);
  }
});

test("token is never written to localStorage/sessionStorage", () => {
  for (const src of [apiClient, view]) {
    assert.doesNotMatch(src, /\blocalStorage\s*\./);
    assert.doesNotMatch(src, /\bsessionStorage\s*\./);
  }
});

test("token is never logged through console.*", () => {
  for (const src of [apiClient, view]) {
    assert.doesNotMatch(src, /console\.(log|info|debug|warn|error)/);
  }
});
