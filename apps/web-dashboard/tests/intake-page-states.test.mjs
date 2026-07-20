// §7 — representative page-state adoption proof (documents + inbound-events).
//
// Source-inspection (the established page-test style in this repo): proves the two representative
// read pages adopt the shared state language, distinguish access-denial from a dependency failure,
// keep the retry an idempotent GET (no mutation control), and never render a raw backend body or an
// internal identifier (correlationId/stack/URL) into the browser.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const documentsPage = readFileSync(join(root, "app", "(dashboard)", "documents", "page.tsx"), "utf8");
const inboundPage = readFileSync(join(root, "app", "(dashboard)", "inbound-events", "page.tsx"), "utf8");

for (const [name, page] of [
  ["documents", documentsPage],
  ["inbound-events", inboundPage]
]) {
  test(`${name}: adopts the shared state primitives`, () => {
    assert.match(page, /from "@\/components\/page-states"/);
    assert.match(page, /\bEmptyState\b/);
    assert.match(page, /\bErrorState\b/);
    assert.match(page, /\bAccessDeniedState\b/);
  });

  test(`${name}: distinguishes access denial, contract drift, and dependency failure`, () => {
    assert.match(page, /code === "ACCESS_DENIED"/);
    assert.match(page, /code === "AUTH_REQUIRED"/);
    assert.match(page, /code === "CONTRACT_ERROR"/);
    // The denied branch renders AccessDeniedState, not the generic ErrorState.
    assert.match(page, /accessDenied\s*\?[\s\S]*?<AccessDeniedState/);
    assert.match(page, /contractError\s*\?[\s\S]*?Response could not be understood/);
  });

  test(`${name}: retry is an idempotent GET navigation, not a mutation control`, () => {
    // Retry is a <Link> back to the same route; no form / POST / submit button in the page.
    assert.match(page, /<Link className="secondary-button table-link-button" href=/);
    assert.doesNotMatch(page, /<form/);
    assert.doesNotMatch(page, /method="post"/i);
    assert.doesNotMatch(page, /<button/);
  });

  test(`${name}: never renders raw backend detail or internal identifiers`, () => {
    assert.doesNotMatch(page, /correlationId/);
    assert.doesNotMatch(page, /stackTrace|\.message\b|error\.stack/);
    assert.doesNotMatch(page, /JSON\.stringify|<pre/);
    // Only the redacted `error` string (from the public-error mapper) is shown as a description.
    assert.match(page, /description=\{error\}/);
  });
}
