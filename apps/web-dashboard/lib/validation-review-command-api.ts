import { caughtUiErrorMessage } from "./ui-error.ts";
import {
  dashboardCoreApiBaseUrl,
  enrichDashboardRequestInit,
  isDashboardApiAuthorityAvailable,
  usesBffTransport
} from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-14D Operator Validation Review command client.
// Typed, tenant-scoped helpers over the OP-CAP-14C backend command endpoints ONLY:
//   POST /api/v1/validations/{validationRunId}/review/corrections
//   POST /api/v1/validations/{validationRunId}/review/issues/{issueId}/resolution
//   POST /api/v1/validations/{validationRunId}/review/approval-requests
// No other mutation is permitted here: no quote/order, product/customer/inventory/price master data,
// ERP/1C, connector, or final approve or reject decision endpoint is called. The backend remains
// authoritative and applies tenant isolation, permission (REVIEW_ACTION), state checks and audit.

export type ApiResult<T> = {
  data: T | null;
  error?: string;
};

export type CorrectionTargetType = "FIELD" | "LINE_ITEM";

export type ValidationReviewCorrectionRequest = {
  targetType: CorrectionTargetType;
  targetId: string;
  correctedValue?: string;
  correctedQuantity?: string;
  correctedUom?: string;
  reason: string;
  clientRequestId?: string;
};

export type IssueResolution = "RESOLVED" | "IGNORED" | "ESCALATED";

export type ValidationIssueResolutionRequest = {
  resolution: IssueResolution;
  reason: string;
  correctionActionId?: string;
  clientRequestId?: string;
};

export type ValidationApprovalRequest = {
  extractedLineItemId?: string;
  requirementType?: string;
  reason: string;
};

// Bounded action result returned by 14C. No raw payload fields.
export type ValidationReviewActionResult = {
  actionId?: string | null;
  validationRunId: string;
  targetType: string;
  targetId: string;
  actionType: string;
  actionStatus: string;
  approvalRequired: boolean;
  approvalRequestId?: string | null;
  resolvedIssueId?: string | null;
  issueResolution?: string | null;
  createdAt?: string | null;
  clientRequestId?: string | null;
  message: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const validationReviewCommandConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  if (usesBffTransport()) {
    return { "Content-Type": "application/json" };
  }
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (validationReviewCommandConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = validationReviewCommandConfig.tenantId;
  }
  return requestHeaders;
}

async function postCommand<T>(path: string, body: unknown): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(validationReviewCommandConfig.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await dashboardApiFetch(
      path,
      enrichDashboardRequestInit({
      method: "POST",
      cache: "no-store",
      headers: headers(),
      body: JSON.stringify(body)
    })
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : null;

    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission for this validation review action (REVIEW_ACTION required)." };
      }
      if (response.status === 404) {
        return { data: null, error: "This review item is no longer available." };
      }
      // 400 and others: surface the bounded backend message only, never a raw dump.
      const message =
        typeof data === "object" && data && "message" in data
          ? String((data as { message?: string }).message)
          : `Core API returned ${response.status}.`;
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }

    return { data };
  } catch (error) {
    return {
      data: null,
      error: caughtUiErrorMessage(error)
    };
  }
}

export function submitValidationReviewCorrection(validationRunId: string, request: ValidationReviewCorrectionRequest) {
  return postCommand<ValidationReviewActionResult>(
    `/api/v1/validations/${validationRunId}/review/corrections`,
    request
  );
}

export function resolveValidationReviewIssue(validationRunId: string, issueId: string, request: ValidationIssueResolutionRequest) {
  return postCommand<ValidationReviewActionResult>(
    `/api/v1/validations/${validationRunId}/review/issues/${issueId}/resolution`,
    request
  );
}

export function requestValidationReviewApproval(validationRunId: string, request: ValidationApprovalRequest) {
  return postCommand<ValidationReviewActionResult>(
    `/api/v1/validations/${validationRunId}/review/approval-requests`,
    request
  );
}
