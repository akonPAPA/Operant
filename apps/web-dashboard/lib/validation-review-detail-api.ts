import { dashboardCoreApiBaseUrl, dashboardRequestHeaders, isDashboardApiAuthorityAvailable } from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-14B Operator Validation Review (detail) API client.
// Typed, tenant-scoped, read-only helper over the OP-CAP-14A backend review contract:
//   GET /api/v1/validations/{validationRunId}/review
//   GET /api/v1/validations/extractions/{extractionResultId}/review
// Read-only by design: no correction, approval, draft/quote/order, ERP/1C, connector or bot mutation.
// The backend remains authoritative; this client never invents business state.

export type ApiResult<T> = {
  data: T | null;
  error?: string;
};

export type ExtractionReviewSummary = {
  extractionResultId: string;
  sourceType?: string;
  sourceId?: string;
  detectedIntent?: string;
  documentType?: string;
  workerStatus?: string | null;
  validationStatus?: string;
  overallConfidence?: number | string | null;
  createdAt?: string | null;
  advisoryOnly: boolean;
};

export type ValidationRunReviewSummary = {
  validationRunId: string;
  status?: string;
  overallStatus?: string;
  routingDecision?: string;
  blockingIssueCount: number;
  warningReviewIssueCount: number;
  approvalRequirementCount: number;
  createdAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
};

export type ExtractedFieldReviewItem = {
  fieldId: string;
  fieldName: string;
  extractedValue?: string | null;
  normalizedValue?: string | null;
  valueType?: string;
  confidence?: number | string | null;
  validationStatus?: string;
  sourceEvidenceId?: string | null;
  issueIds: string[];
};

export type ExtractedLineItemReviewItem = {
  lineItemId: string;
  lineNumber: number;
  rawSku?: string | null;
  matchedProductId?: string | null;
  matchStatus?: string | null;
  description?: string | null;
  quantity?: number | string | null;
  uom?: string | null;
  confidence?: number | string | null;
  validationStatus?: string;
  sourceEvidenceId?: string | null;
  issueIds: string[];
};

export type ValidationIssueReviewItem = {
  issueId: string;
  severity: string;
  code: string;
  message: string;
  targetType: string;
  targetId?: string | null;
  targetLineNumber?: number | null;
  blocking: boolean;
  status: string;
};

export type SourceEvidenceReviewItem = {
  sourceEvidenceId: string;
  evidenceType?: string;
  pageNumber?: number | null;
  startOffset?: number | null;
  endOffset?: number | null;
  snippet?: string | null;
};

export type AuditTimelineItem = {
  action: string;
  entityType: string;
  occurredAt: string;
};

export type AllowedReviewAction = {
  action: string;
  enabled: boolean;
  requiredPermission?: string | null;
};

export type ValidationReviewDetail = {
  extraction: ExtractionReviewSummary;
  validationRun: ValidationRunReviewSummary;
  fields: ExtractedFieldReviewItem[];
  lineItems: ExtractedLineItemReviewItem[];
  issues: ValidationIssueReviewItem[];
  sourceEvidence: SourceEvidenceReviewItem[];
  auditTimeline: AuditTimelineItem[];
  allowedActions: AllowedReviewAction[];
  advisoryOnly: boolean;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const validationReviewDetailConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  return dashboardRequestHeaders(validationReviewDetailConfig.tenantId);
}

// Read-only GET. Tenant id is taken from configured env (never from user input or request body).
async function getJson<T>(path: string): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(validationReviewDetailConfig.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await dashboardApiFetch(path, {
      method: "GET",
      cache: "no-store",
      headers: headers()
    });
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : null;

    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to read this validation review (VALIDATION_READ required)." };
      }
      if (response.status === 404) {
        return { data: null, error: "Validation review not found for this tenant." };
      }
      const message =
        typeof data === "object" && data && "message" in data
          ? String((data as { message?: string }).message)
          : text;
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }

    return { data };
  } catch (error) {
    return {
      data: null,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

export function getValidationReviewByRun(validationRunId: string) {
  return getJson<ValidationReviewDetail>(`/api/v1/validations/${validationRunId}/review`);
}

export function getValidationReviewByExtraction(extractionResultId: string) {
  return getJson<ValidationReviewDetail>(`/api/v1/validations/extractions/${extractionResultId}/review`);
}
