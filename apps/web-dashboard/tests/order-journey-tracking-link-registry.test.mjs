import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-46H — operator tracking-link registry (list) + revoke UI. Source-inspection tests (the
// repo's established frontend test style) prove the contract without a Next.js runtime. Every
// assertion maps to a security/UX requirement: exact GET list route, exact POST revoke route,
// ANALYTICS_READ on read / REVIEW_ACTION on revoke, tenant via header only, business-intent-only
// bodies (no client-owned authority), a list/response type free of token/tokenHash/trackingPath,
// an ACTIVE-only revoke affordance, a duplicate-submit guard, and no token persistence/logging.

const root = process.cwd();
const registryApi = readFileSync(join(root, "lib", "order-journey-tracking-link-registry.ts"), "utf8");
const panel = readFileSync(join(root, "components", "order-journey-tracking-link-registry.tsx"), "utf8");
const detail = readFileSync(join(root, "components", "order-journey-detail.tsx"), "utf8");
const button = readFileSync(join(root, "components", "order-journey-tracking-link-button.tsx"), "utf8");

// Strip own-line `//` comments so the "no internal field" assertions check actual code, not the
// documentation prose (which deliberately names what is withheld). All comments here are own-line.
function codeOnly(src) {
  return src
    .split("\n")
    .filter((line) => !/^\s*\/\//.test(line))
    .join("\n");
}
const registryApiCode = codeOnly(registryApi);

// Backend-owned authority / internal fields the frontend must never put into a request body, nor
// expose as raw token material on the list response. Mirrors the OP-CAP-31 secure business contract.
const FORBIDDEN_FIELDS = [
  "tenantId",
  "actorId",
  "userId",
  "customerId",
  "sourceId",
  "sourceType",
  "sourceRef",
  "actorType",
  "status",
  "milestoneState",
  "evidenceLevel",
  "eta",
  "customerVisible",
  "riskLevel",
  "internalStatus",
  "token",
  "tokenHash",
  "trackingPath"
];

test("order journey detail embeds the tracking links registry panel", () => {
  assert.match(detail, /OrderJourneyTrackingLinkRegistry/);
  assert.match(detail, /journeyId=\{data\.id\}/);
});

test("list client calls the exact GET list route with ANALYTICS_READ and header tenant", () => {
  assert.match(registryApi, /listOrderJourneyTrackingLinks/);
  assert.match(registryApi, /\/api\/v1\/order-journeys\/\$\{encodeURIComponent\(journeyId\)\}\/tracking-links/);
  assert.match(registryApi, /method:\s*"GET"/);
  assert.match(registryApi, /"X-OrderPilot-Permissions":\s*ANALYTICS_READ/);
  assert.match(registryApi, /"X-Tenant-Id":\s*orderJourneyClient\.tenantId/);
});

test("list response type exposes only safe lifecycle metadata — no token/tokenHash/trackingPath", () => {
  assert.match(registryApi, /export type TrackingLinkSummary/);
  assert.match(registryApi, /linkId:\s*string/);
  assert.match(registryApi, /createdAt:\s*string/);
  assert.match(registryApi, /expiresAt:\s*string/);
  assert.match(registryApi, /revokedAt:\s*string\s*\|\s*null/);
  assert.match(registryApi, /status:\s*TrackingLinkStatus/);
  // The actual code (comments stripped) must not declare raw token material or the public path.
  assert.doesNotMatch(registryApiCode, /tokenHash|rawToken|trackingPath/);
  // Nor the public tracking path literal.
  assert.doesNotMatch(registryApiCode, /order-tracking/);
});

test("revoke client calls the exact POST revoke route with REVIEW_ACTION and header tenant", () => {
  assert.match(registryApi, /revokeOrderJourneyTrackingLink/);
  assert.match(
    registryApi,
    /\/api\/v1\/order-journeys\/\$\{encodeURIComponent\(journeyId\)\}`\s*\+\s*\n?\s*`\/tracking-links\/\$\{encodeURIComponent\(linkId\)\}\/revoke/
  );
  assert.match(registryApi, /method:\s*"POST"/);
  assert.match(registryApi, /"X-OrderPilot-Permissions":\s*REVIEW_ACTION/);
});

test("revoke request body is empty business-intent-only — no authority/state/token fields", () => {
  // The revoke body is the empty object literal; the link is identified by the PATH only.
  assert.match(registryApi, /body:\s*JSON\.stringify\(\{\}\)/);
  // No other JSON.stringify call carries any payload fields in this module.
  assert.doesNotMatch(registryApi, /JSON\.stringify\(\{[^}]/);
  const fnStart = registryApi.indexOf("export async function revokeOrderJourneyTrackingLink");
  assert.ok(fnStart >= 0);
  const fnSlice = registryApi.slice(fnStart, fnStart + 1800);
  for (const field of FORBIDDEN_FIELDS) {
    const bodyAssign = new RegExp(`body\\.${field}\\s*=`);
    assert.doesNotMatch(fnSlice, bodyAssign, `revoke body must not set ${field}`);
  }
});

test("non-2xx revoke is thrown with HTTP status attached and never echoes the backend body", () => {
  assert.match(registryApi, /status:\s*response\.status/);
  assert.doesNotMatch(registryApi, /new Error\(text\)|new Error\(JSON\.stringify/);
});

test("panel is a client component and loads the list on mount", () => {
  assert.match(panel, /^"use client";/);
  assert.match(panel, /listOrderJourneyTrackingLinks/);
  assert.match(panel, /useEffect/);
});

test("an ACTIVE link shows a revoke affordance", () => {
  assert.match(panel, /isActive/);
  assert.match(panel, /link\.status === "ACTIVE"/);
  assert.match(panel, /Revoke/);
  assert.match(panel, /revokeOrderJourneyTrackingLink/);
});

test("a revoked / expired link does not show an active revoke affordance", () => {
  // The revoke button is rendered only in the isActive branch; the non-active branch shows static copy.
  assert.match(panel, /isActive \? \(/);
  assert.match(panel, /link\.status === "REVOKED" \? "Revoked" : "Expired"/);
});

test("duplicate concurrent revoke submissions are guarded and the in-flight row is disabled", () => {
  assert.match(panel, /revokingRef/);
  assert.match(panel, /revokingRef\.current\.has\(linkId\)/);
  assert.match(panel, /disabled=\{inFlight\}/);
  assert.match(panel, /Revoking\.\.\./);
});

test("panel surfaces only safe operator messages via mapOperatorActionError", () => {
  assert.match(panel, /mapOperatorActionError/);
  assert.match(panel, /safeMessage/);
  // No raw backend body / stack rendered to the operator.
  assert.doesNotMatch(panel, /JSON\.stringify\(err\)|err\.stack/);
});

test("the registry never persists or logs tracking-link data", () => {
  for (const src of [panel, registryApi]) {
    assert.doesNotMatch(src, /\blocalStorage\s*\./);
    assert.doesNotMatch(src, /\bsessionStorage\s*\./);
    assert.doesNotMatch(src, /console\.(log|info|debug|warn|error)/);
  }
});

test("the create affordance refreshes the registry via a journeyId-only event (no token leak)", () => {
  assert.match(button, /TRACKING_LINK_CREATED_EVENT/);
  assert.match(button, /detail:\s*\{\s*journeyId\s*\}/);
  // The event must not carry token / path / link material.
  assert.doesNotMatch(button, /detail:\s*\{[^}]*trackingPath/);
  assert.match(panel, /TRACKING_LINK_CREATED_EVENT/);
  assert.match(panel, /addEventListener\(TRACKING_LINK_CREATED_EVENT/);
});
