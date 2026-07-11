import "server-only";

import type {
  ReviewDraftQueueFilter,
  ValidationReviewDraftQueueResponse,
  ValidationReviewDraftRecentRemediationRollupResponse,
  ValidationReviewDraftRemediationLineageDetail
} from "../validation-review-draft-queue-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export async function getReviewDraftQueue(filter?: ReviewDraftQueueFilter) {
  const params = new URLSearchParams();
  if (filter?.draftType) params.set("draftType", filter.draftType);
  if (filter?.status) params.set("status", filter.status);
  if (filter?.limit !== undefined) params.set("limit", String(filter.limit));
  if (filter?.offset !== undefined) params.set("offset", String(filter.offset));
  const query = params.toString();
  return tenantServerGetJsonNullable<ValidationReviewDraftQueueResponse>(
    `/api/v1/validations/review-drafts${query ? `?${query}` : ""}`
  );
}

export async function getReviewDraftRemediationLineage(draftKind: string, draftId: string) {
  return tenantServerGetJsonNullable<ValidationReviewDraftRemediationLineageDetail>(
    `/api/v1/validations/review-drafts/${encodeURIComponent(draftKind)}/${encodeURIComponent(draftId)}/remediation-lineage`
  );
}

export async function getReviewDraftRecentRemediationRollup(limit?: number) {
  const path =
    limit !== undefined
      ? `/api/v1/validations/review-drafts/remediation-rollup?limit=${limit}`
      : "/api/v1/validations/review-drafts/remediation-rollup";
  return tenantServerGetJsonNullable<ValidationReviewDraftRecentRemediationRollupResponse>(path);
}
