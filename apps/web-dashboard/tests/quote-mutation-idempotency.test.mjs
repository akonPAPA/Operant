import assert from "node:assert/strict";
import test from "node:test";
import {
  idempotencyKeyForCreateDraftFromRfq,
  idempotencyKeyForQuoteApprovalAction
} from "../lib/quote-mutation-idempotency.ts";
import { IDEMPOTENCY_KEY_CONTRACT } from "../lib/bff/bff-idempotency-key.ts";

test("same RFQ intent yields stable idempotency key", () => {
  const payload = {
    customerExternalRef: "CUST-001",
    requestedLocation: "WH-ALM",
    requestedDiscountPercent: 0,
    requestedItems: [{ rawSkuOrAlias: "SKU", description: "Item", quantity: 2, uom: "EA" }]
  };
  const a = idempotencyKeyForCreateDraftFromRfq(payload);
  const b = idempotencyKeyForCreateDraftFromRfq(payload);
  assert.equal(a, b);
});

test("changed RFQ intent yields different idempotency key", () => {
  const base = {
    customerExternalRef: "CUST-001",
    requestedLocation: "WH-ALM",
    requestedDiscountPercent: 0,
    requestedItems: [{ rawSkuOrAlias: "SKU", description: "Item", quantity: 2, uom: "EA" }]
  };
  const changed = {
    ...base,
    requestedItems: [{ rawSkuOrAlias: "SKU", description: "Item", quantity: 3, uom: "EA" }]
  };
  assert.notEqual(idempotencyKeyForCreateDraftFromRfq(base), idempotencyKeyForCreateDraftFromRfq(changed));
});

test("approval intent binds quote id and reason", () => {
  const a = idempotencyKeyForQuoteApprovalAction({
    action: "approve",
    quoteId: "44444444-4444-4444-8444-444444444444",
    reason: "ok",
    comment: "ok"
  });
  const b = idempotencyKeyForQuoteApprovalAction({
    action: "approve",
    quoteId: "44444444-4444-4444-8444-444444444444",
    reason: "changed",
    comment: "changed"
  });
  assert.notEqual(a, b);
});

test("idempotency keys satisfy BFF canonical grammar", () => {
  const pattern = new RegExp(`^${IDEMPOTENCY_KEY_CONTRACT.pattern.slice(1, -1)}$`);
  const demoKey = idempotencyKeyForCreateDraftFromRfq({
    customerExternalRef: "CUST-001",
    requestedLocation: "WH-ALM",
    requestedDiscountPercent: 0,
    requestedItems: [
      {
        rawSkuOrAlias: "PAD-OE-04465",
        description: "Original brake pads for Toyota Camry 2018",
        quantity: 2,
        uom: "EA"
      }
    ]
  });
  assert.match(demoKey, pattern);
});

test("idempotency keys exclude tenant and permission material", () => {
  const key = idempotencyKeyForQuoteApprovalAction({
    action: "reject",
    quoteId: "44444444-4444-4444-8444-444444444444",
    reason: "nope",
    comment: "nope"
  });
  assert.doesNotMatch(key, /QUOTE_ACTION|tenant|permission|actor/i);
});
