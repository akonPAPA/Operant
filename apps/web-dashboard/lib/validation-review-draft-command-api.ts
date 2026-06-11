// OP-CAP-15A/15B Validation Review → Draft Quote / Draft Order command client.
// Typed, tenant-scoped helpers over the OP-CAP-15A/15B backend endpoints ONLY:
//   GET  /api/v1/validations/{validationRunId}/review/draft-status
//   POST /api/v1/validations/{validationRunId}/review/draft-quote
//   POST /api/v1/validations/{validationRunId}/review/draft-order
// Creates an internal draft only. No final/approved order, no ERP/1C/connector/master-data write, and
// no other mutation endpoint is called. The backend remains authoritative (tenant isolation,
// REVIEW_ACTION permission, readiness gate, idempotency, audit).

export type ApiResult<T> = {
  data: T | null;
  error?: string;
};

export type ValidationReviewDraftResult = {
  draftId: string;
  draftType: "QUOTE" | "ORDER";
  draftStatus: string;
  sourceReviewId: string;
  createdLineCount: number;
  unresolvedBlockingIssueCount: number;
  unresolvedWarningIssueCount: number;
  approvalRequired: boolean;
  created: boolean;
  alreadyExisted: boolean;
  externalExecution: string;
  nextAction: string;
  nextRoute?: string | null;
};

// OP-CAP-15B read-only draft visibility.
export type ValidationReviewDraftStatus = {
  exists: boolean;
  draftType?: "QUOTE" | "ORDER" | null;
  draftId?: string | null;
  workspacePath?: string | null;
  sourceValidationRunId: string;
  sourceExceptionCaseId?: string | null;
  lineCount: number;
  createdAt?: string | null;
  externalExecution: string;
};

// OP-CAP-15B optional create inputs.
export type CreateDraftOptions = {
  selectedLineIds?: string[];
  operatorNote?: string;
};

type BlockingReason = { issueCode?: string; reason?: string };

const DEFAULT_BASE_URL = "http://localhost:8080";

export const validationReviewDraftConfig = {
  baseUrl: process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

function headers() {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (validationReviewDraftConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = validationReviewDraftConfig.tenantId;
  }
  return requestHeaders;
}

// Build the bounded request body. selectedLineIds is sent only when the caller supplies a subset; an
// omitted subset preserves OP-CAP-15A all-lines behavior. operatorNote is trimmed and sent when present.
function draftBody(options?: CreateDraftOptions): string {
  const body: Record<string, unknown> = {};
  if (options?.selectedLineIds !== undefined) body.selectedLineIds = options.selectedLineIds;
  if (options?.operatorNote !== undefined && options.operatorNote.trim() !== "") body.operatorNote = options.operatorNote.trim();
  return JSON.stringify(body);
}

// POST a 15A/15B draft command. Tenant id comes from configured env (never from user input or request body).
// Errors map to bounded, user-safe messages — never a stack trace or raw backend internals.
async function postDraftCommand(path: string, options?: CreateDraftOptions): Promise<ApiResult<ValidationReviewDraftResult>> {
  if (!validationReviewDraftConfig.tenantId) {
    return { data: null, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to create tenant-scoped drafts." };
  }

  try {
    const response = await fetch(`${validationReviewDraftConfig.baseUrl}${path}`, {
      method: "POST",
      cache: "no-store",
      headers: headers(),
      body: draftBody(options)
    });
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;

    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to create a draft from this review (REVIEW_ACTION required)." };
      }
      if (response.status === 404) {
        return { data: null, error: "This validation review is no longer available." };
      }
      if (response.status === 409) {
        // Readiness gate: draft preparation blocked by unresolved validation issues.
        const reasons: BlockingReason[] = Array.isArray(data?.blockingReasons) ? data.blockingReasons : [];
        const detail = reasons.map((r) => r.reason ?? r.issueCode).filter(Boolean).join("; ");
        return { data: null, error: detail ? `Draft blocked: ${detail}` : "Draft blocked by unresolved validation issues." };
      }
      // 400 and others: surface the bounded backend validation message only, never a raw dump.
      const message = typeof data === "object" && data && "message" in data ? String(data.message) : `Core API returned ${response.status}.`;
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }

    return { data: data as ValidationReviewDraftResult };
  } catch (error) {
    return {
      data: null,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

export async function getValidationReviewDraftStatus(validationRunId: string): Promise<ApiResult<ValidationReviewDraftStatus>> {
  if (!validationReviewDraftConfig.tenantId) {
    return { data: null, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped draft status." };
  }
  try {
    const response = await fetch(
      `${validationReviewDraftConfig.baseUrl}/api/v1/validations/${validationRunId}/review/draft-status`,
      { method: "GET", cache: "no-store", headers: headers() }
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as ValidationReviewDraftStatus) : null;
    if (!response.ok) {
      if (response.status === 403) {
        return { data: null, error: "You do not have permission to read draft status (VALIDATION_READ required)." };
      }
      if (response.status === 404) {
        return { data: null, error: "This validation review is no longer available." };
      }
      return { data: null, error: `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: null, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

export function createDraftQuoteFromReview(validationRunId: string, options?: CreateDraftOptions) {
  return postDraftCommand(`/api/v1/validations/${validationRunId}/review/draft-quote`, options);
}

export function createDraftOrderFromReview(validationRunId: string, options?: CreateDraftOptions) {
  return postDraftCommand(`/api/v1/validations/${validationRunId}/review/draft-order`, options);
}
