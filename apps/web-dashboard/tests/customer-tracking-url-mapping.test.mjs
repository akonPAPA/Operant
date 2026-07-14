import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// OP-CAP-46F — Customer-Facing Tracking URL Mapping.
//
// The backend mint response carries the BACKEND public API path
// `/api/v1/public/order-tracking/{token}`. Operators must share the CUSTOMER-FACING frontend page
// `/public/order-tracking/{token}`. These tests prove the pure mapping helper behaviorally (the
// mapping is the whole point of this slice) and prove — by source inspection, the repo's
// established frontend test style — that the operator panel displays/copies the customer-facing
// URL, never the backend API endpoint, with no token persistence/logging and a safe malformed
// state. The OP-CAP-46E public page contract is unchanged (asserted below).

import {
  toCustomerTrackingHref,
  toCustomerTrackingPath
} from "../lib/order-journey-customer-tracking-url.ts";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "order-journey-api.ts"), "utf8");
const button = readFileSync(join(root, "components", "order-journey-tracking-link-button.tsx"), "utf8");
const publicApiClient = readFileSync(join(root, "lib", "public-order-tracking-api.ts"), "utf8");

// --- Behavioral: the pure mapping helper -----------------------------------------------------

test("backend trackingPath maps to the customer-facing frontend path", () => {
  assert.equal(
    toCustomerTrackingPath("/api/v1/public/order-tracking/abc123"),
    "/public/order-tracking/abc123"
  );
});

test("an already-frontend path is returned unchanged", () => {
  assert.equal(
    toCustomerTrackingPath("/public/order-tracking/abc123"),
    "/public/order-tracking/abc123"
  );
});

test("the opaque token is preserved exactly as a single path segment", () => {
  // Tokens may carry URL-safe punctuation and percent-encoding — they must pass through verbatim,
  // never re-encoded (which would corrupt the credential) and never truncated.
  for (const token of ["abc123", "AbC_-9.xyz", "Zm9vYmFy", "a%2Bb%3Dc", "0123456789abcdef"]) {
    assert.equal(
      toCustomerTrackingPath(`/api/v1/public/order-tracking/${token}`),
      `/public/order-tracking/${token}`
    );
  }
});

test("surrounding whitespace is tolerated but the token is otherwise untouched", () => {
  assert.equal(
    toCustomerTrackingPath("  /api/v1/public/order-tracking/abc123  "),
    "/public/order-tracking/abc123"
  );
});

test("malformed / unexpected trackingPath shapes return null (never the backend API path)", () => {
  const malformed = [
    "",
    "/api/v1/public/order-tracking/", // empty token
    "/public/order-tracking/", // empty token, frontend prefix
    "/api/v1/public/order-tracking/abc/extra", // multi-segment token (traversal/extra path)
    "/api/v1/order-journeys/123/tracking-links", // unrelated backend path
    "/api/v2/public/order-tracking/abc123", // wrong version prefix
    "https://evil.example/api/v1/public/order-tracking/abc123", // absolute, not the strict prefix
    "abc123" // bare token, no prefix
  ];
  for (const value of malformed) {
    assert.equal(toCustomerTrackingPath(value), null, `expected null for ${JSON.stringify(value)}`);
  }
});

test("toCustomerTrackingHref is absolute only when an origin is provided", () => {
  assert.equal(
    toCustomerTrackingHref("/api/v1/public/order-tracking/abc123"),
    "/public/order-tracking/abc123"
  );
  assert.equal(
    toCustomerTrackingHref("/api/v1/public/order-tracking/abc123", "https://app.example"),
    "https://app.example/public/order-tracking/abc123"
  );
  // A trailing slash on origin does not double up.
  assert.equal(
    toCustomerTrackingHref("/api/v1/public/order-tracking/abc123", "https://app.example/"),
    "https://app.example/public/order-tracking/abc123"
  );
});

test("toCustomerTrackingHref returns null for a malformed trackingPath (no API path leak)", () => {
  assert.equal(toCustomerTrackingHref("/api/v1/public/order-tracking/", "https://app.example"), null);
  assert.equal(toCustomerTrackingHref("/api/v1/order-journeys/123/tracking-links"), null);
});

// --- Source inspection: the operator panel ----------------------------------------------------

test("the mint API still posts to the exact OP-CAP-46C endpoint", () => {
  assert.match(apiClient, /\/api\/v1\/order-journeys\/\$\{encodeURIComponent\(journeyId\)\}\/tracking-links/);
  assert.match(apiClient, /method:\s*"POST"/);
});

test("the mapping helpers are exported and use strict prefixes, not a fragile replace", () => {
  assert.match(apiClient, /order-journey-customer-tracking-url/);
  const mappingModule = readFileSync(join(root, "lib", "order-journey-customer-tracking-url.ts"), "utf8");
  assert.match(mappingModule, /export function toCustomerTrackingPath/);
  assert.match(mappingModule, /export function toCustomerTrackingHref/);
  assert.match(mappingModule, /"\/api\/v1\/public\/order-tracking\/"/);
  assert.match(mappingModule, /"\/public\/order-tracking\/"/);
  // No fragile string replace that could rewrite an unrelated path.
  assert.doesNotMatch(mappingModule, /trackingPath\.replace\(/);
});

test("the panel displays/copies the customer-facing path via the helper, not the backend endpoint", () => {
  assert.match(button, /toCustomerTrackingPath/);
  assert.match(button, /toCustomerTrackingHref/);
  // The panel renders the mapped customer path, labelled as the customer tracking page.
  assert.match(button, /customerTrackingPath/);
  assert.match(button, /Customer tracking page/);
  assert.match(button, /data-testid="customer-tracking-path"/);
  // The backend API endpoint literal must never appear in the operator panel as a share link.
  assert.doesNotMatch(button, /\/api\/v1\/public\/order-tracking\//);
});

test("origin (window) is read only in client-only code, never at module scope", () => {
  // The single window read sits inside the copy event handler.
  assert.match(button, /typeof window !== "undefined" \? window\.location\.origin : undefined/);
});

test("a malformed backend trackingPath shows a safe error, not the raw path/token", () => {
  assert.match(button, /could not be prepared\. Please retry\./);
  // The malformed branch renders the safe message instead of a path/copy control.
  assert.match(button, /customerTrackingPath \? \(/);
});

test("the panel still shows expiry based on expiresAt", () => {
  assert.match(button, /Expires at/);
  assert.match(button, /formatExpiry\(link\.expiresAt\)/);
});

test("no token/path persistence or logging in the panel or API client", () => {
  for (const src of [button, apiClient]) {
    assert.doesNotMatch(src, /\blocalStorage\s*\./);
    assert.doesNotMatch(src, /\bsessionStorage\s*\./);
    assert.doesNotMatch(src, /console\.(log|info|debug|warn|error)/);
  }
});

// --- Regression: the OP-CAP-46E public page still fetches the BACKEND endpoint -----------------

test("the public page client still calls GET /api/v1/public/order-tracking/{token}", () => {
  assert.match(publicApiClient, /\/api\/v1\/public\/order-tracking\/\$\{encodeURIComponent\(token\)\}/);
  assert.match(publicApiClient, /method:\s*"GET"/);
});
