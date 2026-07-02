import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-07A AI Agent Work Layer (AI Work Assistant) client.
// Surfaces advisory-only AI suggestions for operator/quote/channel work.
// Reads require REVIEW_READ; mutations require AI_WORK_ACTION (re-validated by the backend
// ApiPermissionInterceptor on every request). Accepting a suggestion records operator intent only —
// it never approves a quote/order/discount/substitute and never triggers an external/ERP write.
// No secrets, tokens, or raw provider prompts are exposed by these responses.

export type AiWorkType =
  | "REQUEST_SUMMARY"
  | "VALIDATION_EXPLANATION"
  | "CUSTOMER_REPLY_DRAFT"
  | "NEXT_ACTION_SUGGESTION"
  | "SOURCE_CONTEXT_DIGEST";

export type AiWorkSourceType =
  | "CHANNEL_MESSAGE"
  | "INBOUND_CHANNEL_EVENT"
  | "RFQ_HANDOFF"
  | "OPERATOR_REVIEW"
  | "QUOTE"
  | "QUOTE_TRANSACTION"
  | "SOURCE_CONTEXT";

export type AiWorkStatus = "GENERATED" | "ACCEPTED" | "REJECTED";

export type AiWorkDisplayField = {
  label: string;
  value: string;
  confidence?: number;
  sourceLabel?: string;
};

export type AiWorkEvidenceItem = {
  label: string;
  excerpt?: string;
  page?: number;
  field?: string;
};

export type AiWorkNextActionCandidate = {
  actionCode: string;
  label: string;
  requiresHumanApproval: boolean;
};

export type AiWorkSuggestion = {
  id: string;
  workType: AiWorkType;
  sourceType: AiWorkSourceType;
  status: AiWorkStatus;
  strategyVersion: string;
  riskLevel: "LOW" | "MEDIUM" | "HIGH";
  confidence?: number;
  /** Operator-safe advisory text (not raw provider output). */
  summary: string;
  displayFields: AiWorkDisplayField[];
  evidence: AiWorkEvidenceItem[];
  nextActionCandidates: AiWorkNextActionCandidate[];
  riskFlags: string[];
  advisoryOnly: boolean;
  createdAt: string;
  updatedAt: string;
  decidedAt?: string;
  decisionReason?: string;
};

export type AiWorkDecisionRequest = {
  reason?: string;
};

export type AiWorkApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

// Read vs mutation permissions mirror the backend mapping exactly.
const REVIEW_READ = "REVIEW_READ";
const AI_WORK_ACTION = "AI_WORK_ACTION";

export const aiWorkClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: demoTenantId()
};

// Operator-safe state messages by HTTP status. 403/404 are valid security/business
// outcomes and must not leak whether a tenant-scoped resource exists.
function aiWorkStatusMessage(status: number): string {
  switch (status) {
    case 403:
      return "You do not have access to this workspace or tenant context.";
    case 404:
      return "This suggestion is not found or no longer available.";
    case 422:
      return "This request could not be processed as submitted.";
    default:
      return "Could not load AI work suggestions.";
  }
}

function baseHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  if (aiWorkClient.tenantId) {
    h["X-Tenant-Id"] = aiWorkClient.tenantId;
  }
  return { ...h, ...(extra ?? {}) };
}

async function request<T>(path: string, init: RequestInit, fallback: T): Promise<AiWorkApiResult<T>> {
  if (!aiWorkClient.tenantId) {
    return {
      data: fallback,
      error: "Authenticated dashboard access is unavailable."
    };
  }
  try {
    const response = await fetch(`${aiWorkClient.baseUrl}${path}`, {
      cache: "no-store",
      ...init,
      headers: {
        ...baseHeaders(),
        ...((init.headers as Record<string, string>) ?? {})
      }
    });
    if (!response.ok) {
      // Inspect status before parsing; do not assume a JSON body exists and never
      // surface the raw backend body (it may reference tenant/resource ids).
      return { data: fallback, error: aiWorkStatusMessage(response.status) };
    }
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : fallback;
    return { data };
  } catch (error) {
    return {
      data: fallback,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

// --- Read (REVIEW_READ) ---

export function listRecentAiWork(limit = 50) {
  return request<AiWorkSuggestion[]>(
    `/api/v1/ai-work/suggestions?limit=${encodeURIComponent(limit)}`,
    { method: "GET", headers: { "X-OrderPilot-Permissions": REVIEW_READ } },
    []
  );
}

export function listAiWorkForSource(sourceType: AiWorkSourceType, sourceId: string) {
  const query = `sourceType=${encodeURIComponent(sourceType)}&sourceId=${encodeURIComponent(sourceId)}`;
  return request<AiWorkSuggestion[]>(
    `/api/v1/ai-work/suggestions?${query}`,
    { method: "GET", headers: { "X-OrderPilot-Permissions": REVIEW_READ } },
    []
  );
}

export function getAiWorkSuggestion(id: string) {
  return request<AiWorkSuggestion | null>(
    `/api/v1/ai-work/suggestions/${id}`,
    { method: "GET", headers: { "X-OrderPilot-Permissions": REVIEW_READ } },
    null
  );
}

// --- Mutations (AI_WORK_ACTION) ---

/** Record operator acceptance of the advisory text/idea. Never approves business state. */
export function acceptAiWorkSuggestion(id: string, req?: AiWorkDecisionRequest) {
  return request<AiWorkSuggestion | null>(
    `/api/v1/ai-work/suggestions/${id}/accept`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": AI_WORK_ACTION },
      body: JSON.stringify(req ?? {})
    },
    null
  );
}

/** Record operator rejection, optionally with a reason. */
export function rejectAiWorkSuggestion(id: string, req?: AiWorkDecisionRequest) {
  return request<AiWorkSuggestion | null>(
    `/api/v1/ai-work/suggestions/${id}/reject`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": AI_WORK_ACTION },
      body: JSON.stringify(req ?? {})
    },
    null
  );
}

// --- Display helpers ---

export function workTypeLabel(type: AiWorkType): string {
  switch (type) {
    case "REQUEST_SUMMARY": return "Request summary";
    case "VALIDATION_EXPLANATION": return "Validation explanation";
    case "CUSTOMER_REPLY_DRAFT": return "Customer reply draft";
    case "NEXT_ACTION_SUGGESTION": return "Next-action suggestion";
    case "SOURCE_CONTEXT_DIGEST": return "Source context digest";
    default: return type;
  }
}

export function statusClass(status: AiWorkStatus): string {
  switch (status) {
    case "ACCEPTED": return "done";
    case "REJECTED": return "error";
    case "GENERATED": return "warning";
    default: return "";
  }
}

export function riskClass(risk: string): string {
  switch (risk) {
    case "HIGH": return "error";
    case "MEDIUM": return "warning";
    case "LOW": return "ok";
    default: return "";
  }
}
