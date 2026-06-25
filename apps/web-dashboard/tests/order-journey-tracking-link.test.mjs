import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-46D — operator-facing tracking link affordance. Source-inspection tests prove the
// contract without spinning up Next.js. Every assertion maps to a security/UX requirement in
// the OP-CAP-46C/D contract: tenant via header, REVIEW_ACTION permission, business-intent-only
// body, no client-owned authority fields, no raw token persistence, safe operator errors, and
// the runtime duplicate-click guard. If you change a contract here, update the backend service
// or DTO first and prove it with a backend test.

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "order-journey-api.ts"), "utf8");
const button = readFileSync(join(root, "components", "order-journey-tracking-link-button.tsx"), "utf8");
const detail = readFileSync(join(root, "components", "order-journey-detail.tsx"), "utf8");

// Backend-owned authority / internal fields that the frontend must never put into the
// request body. We assert on body-targeted assignments (e.g. `body.tenantId =`) so that
// non-body references such as `response.status` do not produce false positives. Mirrors
// the OP-CAP-31 secure business contract law.
const FORBIDDEN_BODY_FIELDS = [
  "tenantId",
  "actorId",
  "userId",
  "customerId",
  "sourceId",
  "sourceType",
  "sourceRef",
  "actorType",
  "sortOrder",
  "customerVisible",
  "fulfillmentSignals",
  "riskLevel",
  "internalStatus",
  "approvalStatus",
  "executionStatus",
  "milestoneState",
  "evidenceLevel",
  "ETA",
  "rawPayloadRef",
  "connectorIdempotencyKeyHash",
  "tokenHash"
];

test("API client exposes the typed tracking link result without raw token / hash fields", () => {
  assert.match(apiClient, /export type TrackingLinkCreated/);
  assert.match(apiClient, /trackingPath:\s*string/);
  assert.match(apiClient, /expiresAt:\s*string/);
  // Must not declare token, tokenHash, internal link id, tenant or journey id on the response type.
  assert.doesNotMatch(apiClient, /tokenHash|rawToken|linkId/);
});

test("createOrderJourneyTrackingLink hits the exact OP-CAP-46C endpoint with REVIEW_ACTION", () => {
  assert.match(apiClient, /createOrderJourneyTrackingLink/);
  assert.match(apiClient, /\/api\/v1\/order-journeys\/\$\{encodeURIComponent\(journeyId\)\}\/tracking-links/);
  assert.match(apiClient, /method:\s*"POST"/);
  assert.match(apiClient, /"X-OrderPilot-Permissions":\s*REVIEW_ACTION/);
  assert.match(apiClient, /"X-Tenant-Id":\s*orderJourneyClient\.tenantId/);
});

test("request body is business-intent-only — no backend-owned authority fields", () => {
  for (const field of FORBIDDEN_BODY_FIELDS) {
    const bodyAssign = new RegExp(`body\\.${field}\\s*=`);
    assert.doesNotMatch(apiClient, bodyAssign, `request body must not set ${field}`);
    const bodyLiteral = new RegExp(`${field}\\s*:`);
    // The literal-form check is scoped to the function body slice so unrelated DTOs above
    // (response types, list/detail shapes) do not trip it.
    const fnStart = apiClient.indexOf("export async function createOrderJourneyTrackingLink");
    assert.ok(fnStart >= 0);
    const fnSlice = apiClient.slice(fnStart, fnStart + 2500);
    assert.doesNotMatch(fnSlice, bodyLiteral, `request body literal must not include ${field}`);
  }
  // The only optional input is TTL.
  assert.match(apiClient, /expiresInHours/);
});

test("non-2xx responses are thrown with HTTP status attached and never echo backend body", () => {
  assert.match(apiClient, /status:\s*response\.status/);
  // Must not surface the raw backend body into the thrown error message.
  assert.doesNotMatch(apiClient, /new Error\(text\)|new Error\(JSON\.stringify/);
});

test("button is a client component using the shared operator-action runtime", () => {
  assert.match(button, /^"use client";/);
  assert.match(button, /useOperatorAction/);
  assert.match(button, /mapOperatorActionError/);
});

test("button disables itself while in flight and reflects loading copy", () => {
  assert.match(button, /disabled=\{disabled\}/);
  assert.match(button, /Creating tracking link/);
});

test("button never persists the raw token / path in localStorage or sessionStorage", () => {
  // Allow the substrings inside comments/messages (they document the rule) but forbid the
  // actual storage APIs that would persist the token across pageloads or other tabs.
  assert.doesNotMatch(button, /\blocalStorage\s*\./);
  assert.doesNotMatch(button, /\bsessionStorage\s*\./);
  assert.doesNotMatch(button, /localStorage\.setItem|sessionStorage\.setItem/);
  // No analytics / console logging of the token either.
  assert.doesNotMatch(button, /console\.(log|info|debug|warn|error)\([^)]*trackingPath/);
});

test("button renders the returned link and expiry, with a clear customer-safe warning", () => {
  assert.match(button, /trackingPath/);
  assert.match(button, /Expires at/);
  assert.match(button, /Read-only and customer-safe/);
});

test("copy action guards navigator.clipboard and never leaks the link elsewhere", () => {
  assert.match(button, /navigator\.clipboard/);
  assert.match(button, /writeText\(link\.trackingPath\)/);
  // Must not write the link to any storage or analytics surface.
  assert.doesNotMatch(button, /\blocalStorage\s*\.setItem/);
  assert.doesNotMatch(button, /\bsessionStorage\s*\.setItem/);
});

test("button surfaces only safe operator messages — no raw backend bodies / stack traces", () => {
  assert.match(button, /safeMessage/);
  // No JSON.stringify of an error payload into the UI.
  assert.doesNotMatch(button, /JSON\.stringify\(error\)|err\.stack/);
});

test("order journey detail page embeds the tracking link affordance", () => {
  assert.match(detail, /OrderJourneyTrackingLinkButton/);
  assert.match(detail, /journeyId=\{data\.id\}/);
});
