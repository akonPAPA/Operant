import { dashboardCoreApiBaseUrl, dashboardRequestHeaders, isDashboardApiAuthorityAvailable } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-15C Review-origin draft queue (lite) API client.
// Typed, tenant-scoped, read-only helper over the OP-CAP-15C backend endpoint ONLY:
//   GET /api/v1/validations/review-drafts
// Lists internal drafts created from validation reviews. Read-only: no mutation, no final approval, no
// ERP/1C/connector/external write. Never exposes raw operator-note content (notePresent only). The
// backend remains authoritative (tenant isolation, VALIDATION_READ permission).

export type ApiResult<T> = {
  data: T | null;
  error?: string;
};

// OP-CAP-15G read-only remediation lineage summary, derived from structured records only (counts/booleans).
export type ValidationReviewDraftRemediationSummary = {
  available: boolean;
  draftLineCount: number;
  remediatedDraftLineCount: number;
  correctionActionCount: number;
  issueResolutionActionCount: number;
  approvalActionCount: number;
  limitations: string[];
};

// OP-CAP-15I compact, read-only remediation rollup, derived from the same structured records as 15G.
export type ValidationReviewDraftRemediationRollup = {
  remediationLineageAvailable: boolean;
  remediationActionCount: number;
  remediatedLineCount: number;
  traceableLineCount: number;
  limitationCodes: string[];
  latestRemediationActionAt?: string | null;
};

export type ValidationReviewDraftQueueItem = {
  draftId: string;
  draftType: "QUOTE" | "ORDER";
  sourceValidationRunId?: string | null;
  sourceExceptionCaseId?: string | null;
  customerDisplay?: string | null;
  status: string;
  lineCount: number;
  createdAt: string;
  operatorNotePresent: boolean;
  workspacePath: string;
  reviewPath?: string | null;
  externalExecution: string;
  remediationSummary?: ValidationReviewDraftRemediationSummary | null;
  remediationRollup?: ValidationReviewDraftRemediationRollup | null;
};

export type ValidationReviewDraftQueueResponse = {
  items: ValidationReviewDraftQueueItem[];
  returned: number;
  limit: number;
  offset: number;
  draftTypeFilter?: string | null;
  statusFilter?: string | null;
};

export type ReviewDraftQueueFilter = {
  draftType?: string;
  status?: string;
  limit?: number;
  offset?: number;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const reviewDraftQueueConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  return dashboardRequestHeaders(reviewDraftQueueConfig.tenantId);
}

// OP-CAP-15H read-only remediation lineage DETAIL types. Stable ids + deterministic backend text only —
// never a raw operator note, raw AI payload, prompt or secret.
export type ValidationReviewDraftRemediationLineageAction = {
  operatorActionId: string;
  actionType: string;
  targetType: string;
  targetId: string;
  relatedLineItemId?: string | null;
  relatedIssueId?: string | null;
  relatedApprovalRequirementId?: string | null;
  status?: string | null;
  createdAt: string;
  summary?: string | null;
};

// OP-CAP-15I normalized, deterministic per-line timeline entry (flattened from the attached actions above).
export type LineageTimelineEntry = {
  category: "CORRECTION" | "ISSUE_RESOLUTION" | "APPROVAL";
  actionType: string;
  actionId: string;
  targetLineItemId?: string | null;
  targetIssueId?: string | null;
  targetApprovalRequirementId?: string | null;
  status?: string | null;
  summary?: string | null;
  createdAt: string;
};

export type ValidationReviewDraftRemediationLineageLine = {
  draftLineId: string;
  sourceLineItemId?: string | null;
  sourceLineAvailable: boolean;
  lineNumber: number;
  sku?: string | null;
  description?: string | null;
  quantity?: number | null;
  uom?: string | null;
  correctionActions: ValidationReviewDraftRemediationLineageAction[];
  issueResolutionActions: ValidationReviewDraftRemediationLineageAction[];
  approvalActions: ValidationReviewDraftRemediationLineageAction[];
  limitations: string[];
  timeline: LineageTimelineEntry[];
};

export type ValidationReviewDraftRemediationLineageUnattachedAction = {
  operatorActionId: string;
  actionType: string;
  targetType: string;
  targetId: string;
  category: string;
  limitation: string;
  createdAt: string;
  summary?: string | null;
};

export type ValidationReviewDraftRemediationLineageDetail = {
  draftKind: "QUOTE" | "ORDER";
  draftId: string;
  validationRunId?: string | null;
  sourceExceptionCaseId?: string | null;
  available: boolean;
  limitations: string[];
  draftLineCount: number;
  traceableDraftLineCount: number;
  remediatedDraftLineCount: number;
  correctionActionCount: number;
  issueResolutionActionCount: number;
  approvalActionCount: number;
  lines: ValidationReviewDraftRemediationLineageLine[];
  unattachedActions: ValidationReviewDraftRemediationLineageUnattachedAction[];
  workspacePath: string;
  reviewPath?: string | null;
  externalExecution: string;
};

export function remediationLineagePath(draftKind: string, draftId: string): string {
  return `/workspace/review-drafts/${draftKind}/${draftId}/remediation-lineage`;
}

// OP-CAP-15J — bounded recent-window remediation rollup tile (read-only, tenant-scoped, structured-only).
export type ValidationReviewDraftRecentRemediationRollupItem = {
  draftKind: string;
  draftId: string;
  sourceValidationRunId?: string | null;
  remediationLineageAvailable: boolean;
  remediationActionCount: number;
  remediatedLineCount: number;
  traceableLineCount: number;
  latestRemediationActionAt?: string | null;
  limitationCodes: string[];
};

export type ValidationReviewDraftRecentRemediationRollupResponse = {
  inspectedDraftCount: number;
  reviewOriginDraftCount: number;
  lineageAvailableDraftCount: number;
  lineageUnavailableDraftCount: number;
  draftLineCount: number;
  traceableDraftLineCount: number;
  remediatedDraftLineCount: number;
  remediationActionCount: number;
  correctionActionCount: number;
  issueResolutionActionCount: number;
  approvalActionCount: number;
  latestRemediationActionAt?: string | null;
  limitationCodes: string[];
  topLimitedDrafts: ValidationReviewDraftRecentRemediationRollupItem[];
  limit: number;
  externalExecution: string;
};

// Backend route to the recent remediation rollup tile (used for documentation/tests; the helper builds it).
export function remediationRollupPath(limit?: number): string {
  return `/api/v1/validations/review-drafts/remediation-rollup${limit !== undefined ? `?limit=${limit}` : ""}`;
}

export async function getReviewDraftQueue(filter?: ReviewDraftQueueFilter): Promise<ApiResult<ValidationReviewDraftQueueResponse>> {
  if (!isDashboardApiAuthorityAvailable(reviewDraftQueueConfig.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }
  const params = new URLSearchParams();
  if (filter?.draftType) params.set("draftType", filter.draftType);
  if (filter?.status) params.set("status", filter.status);
  if (filter?.limit !== undefined) params.set("limit", String(filter.limit));
  if (filter?.offset !== undefined) params.set("offset", String(filter.offset));
  const query = params.toString();

  try {
    const response = await fetch(
      `${reviewDraftQueueConfig.baseUrl}/api/v1/validations/review-drafts${query ? `?${query}` : ""}`,
      { method: "GET", cache: "no-store", headers: headers() }
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as ValidationReviewDraftQueueResponse) : null;
    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to read review drafts (VALIDATION_READ required)." };
      }
      if (response.status === 400) {
        return { data: null, error: "Invalid review-draft queue filter." };
      }
      return { data: null, error: `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: null, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

// OP-CAP-15H — read-only remediation lineage detail for one review-origin draft.
//   GET /api/v1/validations/review-drafts/{draftKind}/{draftId}/remediation-lineage
// Tenant-scoped (X-Tenant-Id), VALIDATION_READ. No mutation, no final approval, no external write.
export async function getReviewDraftRemediationLineage(
  draftKind: string,
  draftId: string
): Promise<ApiResult<ValidationReviewDraftRemediationLineageDetail>> {
  if (!isDashboardApiAuthorityAvailable(reviewDraftQueueConfig.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await fetch(
      `${reviewDraftQueueConfig.baseUrl}/api/v1/validations/review-drafts/${encodeURIComponent(draftKind)}/${encodeURIComponent(draftId)}/remediation-lineage`,
      { method: "GET", cache: "no-store", headers: headers() }
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as ValidationReviewDraftRemediationLineageDetail) : null;
    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to read review drafts (VALIDATION_READ required)." };
      }
      if (response.status === 404) {
        return { data: null, error: "That review-origin draft was not found for this tenant." };
      }
      if (response.status === 400) {
        return { data: null, error: "Unsupported draft kind (expected QUOTE or ORDER)." };
      }
      return { data: null, error: `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: null, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

// OP-CAP-15J — read-only recent remediation rollup tile for the review-draft workspace.
//   GET /api/v1/validations/review-drafts/remediation-rollup?limit=50
// Tenant-scoped (X-Tenant-Id), VALIDATION_READ. No mutation, no final approval, no external write.
export async function getReviewDraftRecentRemediationRollup(
  limit?: number
): Promise<ApiResult<ValidationReviewDraftRecentRemediationRollupResponse>> {
  if (!isDashboardApiAuthorityAvailable(reviewDraftQueueConfig.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await fetch(`${reviewDraftQueueConfig.baseUrl}${remediationRollupPath(limit)}`, {
      method: "GET",
      cache: "no-store",
      headers: headers()
    });
    const text = await response.text();
    const data = text ? (JSON.parse(text) as ValidationReviewDraftRecentRemediationRollupResponse) : null;
    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to read review drafts (VALIDATION_READ required)." };
      }
      if (response.status === 400) {
        return { data: null, error: "Invalid recent remediation rollup request." };
      }
      return { data: null, error: `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: null, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}
