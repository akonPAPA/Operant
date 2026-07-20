import { createOperatorIdempotencyKey } from "./operator-action-runtime.ts";
import { IDEMPOTENCY_KEY_CONTRACT } from "./bff/bff-idempotency-key.ts";

function normalizePart(value: string, maxLen: number): string {
  const trimmed = value.trim().replace(/\s+/g, "_");
  if (trimmed.length <= maxLen) {
    return trimmed;
  }
  return trimmed.slice(0, maxLen);
}

function boundKey(raw: string): string {
  if (raw.length <= IDEMPOTENCY_KEY_CONTRACT.maxLength) {
    return raw;
  }
  return raw.slice(0, IDEMPOTENCY_KEY_CONTRACT.maxLength);
}

/** Intent-bound idempotency key for RFQ → draft quote (stable business fields only). */
export function idempotencyKeyForCreateDraftFromRfq(input: {
  customerExternalRef: string;
  requestedLocation: string;
  requestedDiscountPercent: number;
  requestedItems: ReadonlyArray<{
    rawSkuOrAlias: string;
    description: string;
    quantity: number;
    uom: string;
  }>;
}): string {
  const items = input.requestedItems
    .map(
      (line) =>
        `${normalizePart(line.rawSkuOrAlias, 64)}:${line.quantity}:${normalizePart(line.uom, 16)}:${normalizePart(line.description, 128)}`
    )
    .join(".");
  const handle = [
    normalizePart(input.customerExternalRef, 64),
    normalizePart(input.requestedLocation, 32),
    String(input.requestedDiscountPercent),
    items
  ].join(".");
  return boundKey(createOperatorIdempotencyKey("quote-from-rfq", handle));
}

/** Intent-bound idempotency key for quote approval mutations. */
export function idempotencyKeyForQuoteApprovalAction(input: {
  action: "approve" | "reject" | "changes" | "convert";
  quoteId: string;
  reason?: string;
  comment?: string;
  approvalRequestId?: string;
}): string {
  const handle = [
    input.quoteId,
    normalizePart(input.reason ?? "", 512),
    normalizePart(input.comment ?? "", 512),
    normalizePart(input.approvalRequestId ?? "", 64)
  ].join(".");
  return boundKey(createOperatorIdempotencyKey(`quote-${input.action}`, handle));
}
