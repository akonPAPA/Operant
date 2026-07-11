import "server-only";

export {
  getReviewDraftQueue,
  getReviewDraftRemediationLineage,
  getReviewDraftRecentRemediationRollup
} from "./validation-review-draft-queue-reads.server.ts";

export type {
  LineageTimelineEntry,
  ReviewDraftQueueFilter,
  ValidationReviewDraftQueueResponse,
  ValidationReviewDraftRecentRemediationRollupResponse,
  ValidationReviewDraftRemediationLineageDetail,
  ValidationReviewDraftRemediationLineageAction,
  ValidationReviewDraftRemediationLineageLine,
  ValidationReviewDraftRemediationSummary
} from "../validation-review-draft-queue-api.ts";

export function remediationLineagePath(draftKind: string, draftId: string): string {
  return `/workspace/review-drafts/${draftKind}/${draftId}/remediation-lineage`;
}
