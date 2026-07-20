import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { validateBffQuoteMutationRequest } from "../lib/bff/bff-quote-mutation-contract.ts";

const QUOTE_ID = "44444444-4444-4444-8444-444444444444";
const KEY = "550e8400-e29b-41d4-a716-446655440000";
const FROM_RFQ = ["api", "v1", "quotes", "from-rfq"];
const APPROVE = ["api", "v1", "quotes", QUOTE_ID, "approve"];
const REJECT = ["api", "v1", "quotes", QUOTE_ID, "reject"];

const VALID_RFQ = {
  customerExternalRef: "ТОО Астана / CUST-001",
  requestedLocation: "WH/ALM",
  requestedDiscountPercent: 0,
  requestedItems: [{ rawSkuOrAlias: "SKU/2026", description: "Brake pad & sensor", quantity: 2, uom: "EA" }]
};

function request(body, options = {}) {
  const headers = {
    "Content-Type": options.contentType ?? "application/json",
    ...(options.key === null ? {} : { "Idempotency-Key": options.key ?? KEY }),
    ...(options.headers ?? {})
  };
  return new Request("https://dashboard.test/api/bff/api/v1/quotes/from-rfq", {
    method: "POST",
    headers,
    body
  });
}

test("catch-all BFF route runs quote contract before generic proxy", () => {
  const source = readFileSync(join(process.cwd(), "app/api/bff/[...segments]/route.ts"), "utf8");
  assert.ok(source.indexOf("validateBffQuoteMutationRequest") < source.indexOf("proxyCoreRequest(request, segments)"));
});

test("valid Unicode and punctuation business intent passes without leaking into the key", async () => {
  const response = await validateBffQuoteMutationRequest(request(JSON.stringify(VALID_RFQ)), FROM_RFQ);
  assert.equal(response, null);
});

test("missing idempotency key fails closed", async () => {
  const response = await validateBffQuoteMutationRequest(request(JSON.stringify(VALID_RFQ), { key: null }), FROM_RFQ);
  assert.equal(response?.status, 400);
});

test("empty body fails closed", async () => {
  const response = await validateBffQuoteMutationRequest(request(""), FROM_RFQ);
  assert.equal(response?.status, 400);
});

test("unsupported media type fails closed", async () => {
  const response = await validateBffQuoteMutationRequest(request(JSON.stringify(VALID_RFQ), { contentType: "text/plain" }), FROM_RFQ);
  assert.equal(response?.status, 415);
});

test("missing required fields, wrong types and empty arrays fail closed", async () => {
  for (const body of [
    {},
    { ...VALID_RFQ, customerExternalRef: 42 },
    { ...VALID_RFQ, requestedItems: [] },
    { ...VALID_RFQ, requestedDiscountPercent: 101 },
    { ...VALID_RFQ, requestedItems: [{ ...VALID_RFQ.requestedItems[0], quantity: 0 }] },
    { ...VALID_RFQ, requestedItems: [{ ...VALID_RFQ.requestedItems[0], nestedExtra: true }] }
  ]) {
    const response = await validateBffQuoteMutationRequest(request(JSON.stringify(body)), FROM_RFQ);
    assert.equal(response?.status, 400, JSON.stringify(body));
  }
});

test("RFQ item count is bounded", async () => {
  const body = { ...VALID_RFQ, requestedItems: Array.from({ length: 101 }, () => VALID_RFQ.requestedItems[0]) };
  const response = await validateBffQuoteMutationRequest(request(JSON.stringify(body)), FROM_RFQ);
  assert.equal(response?.status, 400);
});

test("duplicate JSON properties are rejected rather than silently collapsed", async () => {
  const raw = `{"customerExternalRef":"CUST-A","customerExternalRef":"CUST-B","requestedItems":[{"rawSkuOrAlias":"SKU","quantity":1,"uom":"EA"}]}`;
  const response = await validateBffQuoteMutationRequest(request(raw), FROM_RFQ);
  assert.equal(response?.status, 400);
});

test("approve accepts an explicit empty intent object but reject requires a reason or comment", async () => {
  assert.equal(await validateBffQuoteMutationRequest(request("{}"), APPROVE), null);
  assert.equal((await validateBffQuoteMutationRequest(request("{}"), REJECT))?.status, 400);
  assert.equal(
    await validateBffQuoteMutationRequest(request(JSON.stringify({ reason: "Customer declined" })), REJECT),
    null
  );
});

test("oversized declared body is denied before parsing", async () => {
  const response = await validateBffQuoteMutationRequest(
    request(JSON.stringify(VALID_RFQ), { headers: { "Content-Length": String(256 * 1024 + 1) } }),
    FROM_RFQ
  );
  assert.equal(response?.status, 413);
});
