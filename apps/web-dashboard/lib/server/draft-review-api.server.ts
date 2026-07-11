import "server-only";

import type {
  DraftReviewDetail,
  DraftReviewQueueParams,
  DraftReviewSummary
} from "../draft-review-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type {
  DraftReviewDetail,
  DraftReviewQueueParams,
  DraftReviewSummary
} from "../draft-review-api.ts";

function queueQuery(params: DraftReviewQueueParams): string {
  const search = new URLSearchParams();
  if (params.status) search.set("status", params.status);
  if (params.sourceReviewCaseId) search.set("sourceReviewCaseId", params.sourceReviewCaseId);
  if (params.customerRef) search.set("customerRef", params.customerRef);
  if (params.limit) search.set("limit", String(params.limit));
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

export function getDraftQuoteReview(draftQuoteId: string) {
  return tenantServerGetJson<DraftReviewDetail>(
    `/api/v1/workspace/draft-quotes/${draftQuoteId}/review`
  );
}

export function getDraftOrderReview(draftOrderId: string) {
  return tenantServerGetJson<DraftReviewDetail>(
    `/api/v1/workspace/draft-orders/${draftOrderId}/review`
  );
}

export function getDraftQuoteReviewQueue(params: DraftReviewQueueParams = {}) {
  return tenantServerGetJson<DraftReviewSummary[]>(
    `/api/v1/workspace/draft-quotes/review-queue${queueQuery(params)}`
  );
}

export function getDraftOrderReviewQueue(params: DraftReviewQueueParams = {}) {
  return tenantServerGetJson<DraftReviewSummary[]>(
    `/api/v1/workspace/draft-orders/review-queue${queueQuery(params)}`
  );
}
