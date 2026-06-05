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
  | "OPERATOR_REVIEW"
  | "QUOTE"
  | "QUOTE_TRANSACTION"
  | "SOURCE_CONTEXT";

export type AiWorkStatus = "GENERATED" | "ACCEPTED" | "REJECTED";

export type AiWorkSuggestion = {
  id: string;
  workType: AiWorkType;
  sourceType: AiWorkSourceType;
  sourceId: string;
  status: AiWorkStatus;
  strategyVersion: string;
  riskLevel: "LOW" | "MEDIUM" | "HIGH";
  confidence?: number;
  generatedText: string;
  /** JSON string the UI parses for structured display (e.g. next-action candidates). */
  structuredPayloadJson: string;
  /** JSON string anchoring the suggestion to its source evidence. */
  evidenceRefsJson: string;
  advisoryOnly: boolean;
  createdByUserId?: string;
  createdAt: string;
  updatedAt: string;
  decidedByUserId?: string;
  decidedAt?: string;
  decisionReason?: string;
};

export type CreateAiWorkSuggestionRequest = {
  workType: AiWorkType;
  sourceType: AiWorkSourceType;
  sourceId: string;
  contextText?: string;
  idempotencyKey?: string;
  createdByUserId?: string;
};

export type AiWorkDecisionRequest = {
  decidedByUserId?: string;
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
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

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
      error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped AI work suggestions."
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
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : fallback;
    if (!response.ok) {
      const message =
        typeof data === "object" && data && "message" in data
          ? String((data as { message?: string }).message)
          : "";
      return { data: fallback, error: message || `Core API returned ${response.status}.` };
    }
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

/** Generate a new advisory suggestion. Does not mutate any business record. */
export function createAiWorkSuggestion(req: CreateAiWorkSuggestionRequest) {
  return request<AiWorkSuggestion | null>(
    "/api/v1/ai-work/suggestions",
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": AI_WORK_ACTION },
      body: JSON.stringify(req)
    },
    null
  );
}

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

export const AI_WORK_TYPES: AiWorkType[] = [
  "REQUEST_SUMMARY",
  "VALIDATION_EXPLANATION",
  "CUSTOMER_REPLY_DRAFT",
  "NEXT_ACTION_SUGGESTION",
  "SOURCE_CONTEXT_DIGEST"
];

export const AI_WORK_SOURCE_TYPES: AiWorkSourceType[] = [
  "CHANNEL_MESSAGE",
  "INBOUND_CHANNEL_EVENT",
  "OPERATOR_REVIEW",
  "QUOTE",
  "QUOTE_TRANSACTION",
  "SOURCE_CONTEXT"
];

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

/** Safely parse a structured payload JSON string for display; never throws. */
export function parseStructuredPayload(json: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(json) as unknown;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export type NextActionCandidate = {
  actionCode: string;
  label: string;
  requiresHumanApproval: boolean;
};

export function extractNextActions(json: string): NextActionCandidate[] {
  const payload = parseStructuredPayload(json);
  const candidates = payload["candidates"];
  if (!Array.isArray(candidates)) return [];
  return candidates
    .filter((c): c is NextActionCandidate => !!c && typeof c === "object" && "actionCode" in c)
    .map((c) => ({
      actionCode: String(c.actionCode),
      label: String(c.label ?? c.actionCode),
      requiresHumanApproval: c.requiresHumanApproval !== false
    }));
}
