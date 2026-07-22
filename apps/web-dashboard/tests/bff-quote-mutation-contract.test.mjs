/**
 * Unit contract for the quote-authority business payload schema (bff-quote-mutation-contract).
 *
 * F1 security-order: this module is now a set of PURE functions that operate on already-read bytes
 * / already-parsed objects. It no longer reads the request body, so the proxy owns the single
 * bounded read and the auth-before-body ordering. Behavioural auth-order proof lives in
 * bff-quote-mutation-matrix.test.mjs; these tests lock the pure classification and schema.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  parseStrictQuoteJsonBody,
  quoteMutationKind,
  validateQuoteMutationBody
} from "../lib/bff/bff-quote-mutation-contract.ts";

const QUOTE_ID = "44444444-4444-4444-8444-444444444444";
const FROM_RFQ = ["api", "v1", "quotes", "from-rfq"];
const APPROVE = ["api", "v1", "quotes", QUOTE_ID, "approve"];
const REJECT = ["api", "v1", "quotes", QUOTE_ID, "reject"];

const VALID_RFQ = {
  customerExternalRef: "ТОО Астана / CUST-001",
  requestedLocation: "WH/ALM",
  requestedDiscountPercent: 0,
  requestedItems: [{ rawSkuOrAlias: "SKU/2026", description: "Brake pad & sensor", quantity: 2, uom: "EA" }]
};

const bytes = (value) => new TextEncoder().encode(value);

test("catch-all BFF route delegates to the proxy without reading or parsing the body", () => {
  const source = readFileSync(join(process.cwd(), "app/api/bff/[...segments]/route.ts"), "utf8");
  // The handler must call proxyCoreRequest and nothing else authority/body-related.
  assert.match(source, /return proxyCoreRequest\(request, segments\)/);
  // No body consumption or pre-proxy quote validation may live in the route handler.
  assert.doesNotMatch(source, /validateBffQuoteMutationRequest/);
  assert.doesNotMatch(source, /request\.(clone|json|text|arrayBuffer|body)/);
});

test("quoteMutationKind classifies exactly the five quote-authority mutations", () => {
  assert.equal(quoteMutationKind(FROM_RFQ, "POST"), "from-rfq");
  assert.equal(quoteMutationKind(APPROVE, "POST"), "approve");
  assert.equal(quoteMutationKind(REJECT, "POST"), "reject");
  assert.equal(quoteMutationKind(["api", "v1", "quotes", QUOTE_ID, "request-changes"], "POST"), "request-changes");
  assert.equal(
    quoteMutationKind(["api", "v1", "quotes", QUOTE_ID, "convert-to-internal-order"], "POST"),
    "convert-to-internal-order"
  );
  // Not quote-authority: wrong method, non-uuid id, unknown action, or unrelated route.
  assert.equal(quoteMutationKind(FROM_RFQ, "GET"), null);
  assert.equal(quoteMutationKind(["api", "v1", "quotes", "not-a-uuid", "approve"], "POST"), null);
  assert.equal(quoteMutationKind(["api", "v1", "quotes", QUOTE_ID, "delete-everything"], "POST"), null);
  assert.equal(quoteMutationKind(["api", "v1", "quote-review", QUOTE_ID, "assemble-draft"], "POST"), null);
});

test("parseStrictQuoteJsonBody accepts a bounded plain object and strips a BOM", () => {
  const parsed = parseStrictQuoteJsonBody(bytes(JSON.stringify(VALID_RFQ)));
  assert.equal(parsed.ok, true);
  assert.equal(parsed.object.customerExternalRef, VALID_RFQ.customerExternalRef);

  const withBom = parseStrictQuoteJsonBody(bytes("\uFEFF" + JSON.stringify({ reason: "ok" })));
  assert.equal(withBom.ok, true);
  assert.equal(withBom.object.reason, "ok");
});

test("parseStrictQuoteJsonBody rejects duplicate keys on the raw bytes (never collapse-first)", () => {
  const raw = `{"customerExternalRef":"CUST-A","customerExternalRef":"CUST-B","requestedItems":[]}`;
  assert.equal(parseStrictQuoteJsonBody(bytes(raw)).ok, false);
});

test("parseStrictQuoteJsonBody rejects malformed JSON, invalid UTF-8 and non-objects", () => {
  assert.equal(parseStrictQuoteJsonBody(bytes("{not-json")).ok, false);
  assert.equal(parseStrictQuoteJsonBody(new Uint8Array([0x7b, 0x22, 0x61, 0x22, 0x3a, 0xff, 0x7d])).ok, false);
  assert.equal(parseStrictQuoteJsonBody(bytes("[1,2,3]")).ok, false);
  assert.equal(parseStrictQuoteJsonBody(bytes('"a string"')).ok, false);
  assert.equal(parseStrictQuoteJsonBody(bytes("42")).ok, false);
});

test("validateQuoteMutationBody enforces the from-rfq value schema", () => {
  assert.equal(validateQuoteMutationBody("from-rfq", VALID_RFQ), true);
  for (const body of [
    {},
    { ...VALID_RFQ, customerExternalRef: 42 },
    { ...VALID_RFQ, customerExternalRef: "   " },
    { ...VALID_RFQ, requestedItems: [] },
    { ...VALID_RFQ, requestedDiscountPercent: 101 },
    { ...VALID_RFQ, requestedItems: [{ ...VALID_RFQ.requestedItems[0], quantity: 0 }] },
    { ...VALID_RFQ, requestedItems: Array.from({ length: 101 }, () => VALID_RFQ.requestedItems[0]) }
  ]) {
    assert.equal(validateQuoteMutationBody("from-rfq", body), false, JSON.stringify(body).slice(0, 60));
  }
});

test("validateQuoteMutationBody: approve accepts empty intent; reject/request-changes need a reason or comment", () => {
  assert.equal(validateQuoteMutationBody("approve", {}), true);
  assert.equal(validateQuoteMutationBody("reject", {}), false);
  assert.equal(validateQuoteMutationBody("request-changes", {}), false);
  assert.equal(validateQuoteMutationBody("reject", { reason: "Customer declined" }), true);
  assert.equal(validateQuoteMutationBody("request-changes", { comment: "Fix qty" }), true);
  assert.equal(validateQuoteMutationBody("approve", { approvalRequestId: "not-a-uuid" }), false);
  assert.equal(validateQuoteMutationBody("approve", { reason: "x".repeat(2001) }), false);
});
