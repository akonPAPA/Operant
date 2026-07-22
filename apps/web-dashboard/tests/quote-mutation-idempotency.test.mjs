import assert from "node:assert/strict";
import test from "node:test";
import {
  idempotencyKeyForCreateDraftFromRfq,
  idempotencyKeyForQuoteApprovalAction,
  intentFingerprintForCreateDraftFromRfq,
  intentFingerprintForQuoteApprovalAction
} from "../lib/quote-mutation-idempotency.ts";
import { IDEMPOTENCY_KEY_CONTRACT } from "../lib/bff/bff-idempotency-key.ts";

const RFQ = {
  customerExternalRef: "ТОО Астана / CUST-001",
  requestedLocation: "WH/ALM",
  requestedDiscountPercent: 0,
  requestedItems: [
    {
      rawSkuOrAlias: "SKU/2026",
      description: "Brake pad & sensor, цена > 100",
      quantity: 2,
      uom: "EA"
    }
  ]
};

test("same RFQ intent yields a stable browser-memory fingerprint", () => {
  assert.equal(intentFingerprintForCreateDraftFromRfq(RFQ), intentFingerprintForCreateDraftFromRfq(RFQ));
});

test("changed RFQ intent yields a different browser-memory fingerprint", () => {
  const changed = { ...RFQ, requestedItems: [{ ...RFQ.requestedItems[0], quantity: 3 }] };
  assert.notEqual(intentFingerprintForCreateDraftFromRfq(RFQ), intentFingerprintForCreateDraftFromRfq(changed));
});

test("approval fingerprint binds action, quote, reason and comment", () => {
  const base = {
    action: "approve",
    quoteId: "44444444-4444-4444-8444-444444444444",
    reason: "ok",
    comment: "ok"
  };
  assert.notEqual(
    intentFingerprintForQuoteApprovalAction(base),
    intentFingerprintForQuoteApprovalAction({ ...base, reason: "changed" })
  );
});

test("each intentional attempt receives a new opaque idempotency key", () => {
  assert.notEqual(idempotencyKeyForCreateDraftFromRfq(RFQ), idempotencyKeyForCreateDraftFromRfq(RFQ));
});

test("quote attempt keys satisfy the canonical BFF grammar", () => {
  const pattern = new RegExp(`^${IDEMPOTENCY_KEY_CONTRACT.pattern.slice(1, -1)}$`);
  assert.match(idempotencyKeyForCreateDraftFromRfq(RFQ), pattern);
  assert.match(
    idempotencyKeyForQuoteApprovalAction({
      action: "reject",
      quoteId: "44444444-4444-4444-8444-444444444444",
      reason: "Цена > 100, согласовано",
      comment: "customer text"
    }),
    pattern
  );
});

test("idempotency headers contain no customer, SKU, reason, tenant or permission material", () => {
  const key = idempotencyKeyForCreateDraftFromRfq(RFQ);
  assert.doesNotMatch(key, /Астана|CUST|SKU|Brake|tenant|permission|QUOTE_ACTION/i);
  assert.ok(key.length <= IDEMPOTENCY_KEY_CONTRACT.maxLength);
});
