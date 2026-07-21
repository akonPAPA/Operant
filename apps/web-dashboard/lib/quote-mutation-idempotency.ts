import { generateIdempotencyKey } from "./security-idempotency.ts";

export type CreateDraftQuoteIntent = {
  customerExternalRef: string;
  requestedLocation: string;
  requestedDiscountPercent: number;
  requestedItems: ReadonlyArray<{
    rawSkuOrAlias: string;
    description: string;
    quantity: number;
    uom: string;
  }>;
};

export type QuoteApprovalIntent = {
  action: "approve" | "reject" | "changes" | "convert";
  quoteId: string;
  reason?: string;
  comment?: string;
  approvalRequestId?: string;
};

/**
 * Browser-memory-only fingerprint. It is never sent in a header or persisted, so business text
 * cannot leak through proxy/access logs. Explicit property ordering makes equal intents stable
 * without truncation or collision-prone concatenation.
 */
export function intentFingerprintForCreateDraftFromRfq(input: CreateDraftQuoteIntent): string {
  return JSON.stringify({
    customerExternalRef: input.customerExternalRef,
    requestedLocation: input.requestedLocation,
    requestedDiscountPercent: input.requestedDiscountPercent,
    requestedItems: input.requestedItems.map((item) => ({
      rawSkuOrAlias: item.rawSkuOrAlias,
      description: item.description,
      quantity: item.quantity,
      uom: item.uom
    }))
  });
}

/** Browser-memory-only fingerprint for one approval business intent. */
export function intentFingerprintForQuoteApprovalAction(input: QuoteApprovalIntent): string {
  return JSON.stringify({
    action: input.action,
    quoteId: input.quoteId,
    reason: input.reason ?? "",
    comment: input.comment ?? "",
    approvalRequestId: input.approvalRequestId ?? ""
  });
}

/**
 * Opaque attempt-scoped key sent to BFF/Core. A pending or transport-uncertain retry reuses this
 * value through the component attempt map; a confirmed terminal result clears it, so a later
 * intentional operation with identical business input receives a new key.
 */
export function idempotencyKeyForCreateDraftFromRfq(_input: CreateDraftQuoteIntent): string {
  return generateIdempotencyKey();
}

/** Opaque attempt-scoped key for quote approval mutations. */
export function idempotencyKeyForQuoteApprovalAction(_input: QuoteApprovalIntent): string {
  return generateIdempotencyKey();
}
