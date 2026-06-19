import {
  ApiResult,
  coreApiBaseUrl,
  coreApiGet,
  coreApiStatusMessage,
  demoScopeHeaders
} from "@/lib/core-api-client";

export type QuoteReviewQueueRow = {
  quoteId: string;
  sourceType?: string | null;
  sourceChannel?: string | null;
  customer: { customerAccountId?: string | null; displayName?: string | null; resolutionStatus: string };
  lineCount: number;
  validationIssueCount: number;
  highestSeverity: string;
  status: string;
  createdAt: string;
  nextRequiredAction: string;
};

export type QuoteReviewDetail = {
  header: {
    quoteId: string;
    quoteNumber: string;
    customer: QuoteReviewQueueRow["customer"];
    currency: string;
    subtotalAmount?: number | string | null;
    discountAmount?: number | string | null;
    totalAmount?: number | string | null;
    marginPercent?: number | string | null;
    requiresHumanReview: boolean;
    createdAt: string;
  };
  status: string;
  sourceContext?: {
    sourceType: string;
    sourceChannel?: string | null;
    sourceReceivedAt?: string | null;
    conversionStatus?: string | null;
  } | null;
  conversionAttempt?: {
    id: string;
    sourceType: string;
    status: string;
    failureCode?: string | null;
    failureMessage?: string | null;
    requestMode: string;
    triggeredByType: string;
  } | null;
  sourceLines: Array<{ lineNumber: number; rawSkuOrAlias?: string | null; description?: string | null; quantity?: number | string | null; uom?: string | null; status?: string | null }>;
  draftQuoteLines: Array<{ id: string; lineNumber: number; rawSkuOrAlias?: string | null; productId?: string | null; productName?: string | null; quantity: number | string; uom: string; unitPrice?: number | string | null; marginPercent?: number | string | null; validationStatus: string }>;
  validationIssues: Array<{ id: string; lineId?: string | null; issueCode: string; severity: string; blocking: boolean; message: string; status: string }>;
  proposedSubstitutes: Array<{ lineId?: string | null; productId: string; sku: string; productName: string; riskLevel: string; reasonCode: string; availableStock?: number | string | null; stockStatus: string; requiresApproval: boolean; blocked: boolean; explanation: string }>;
  pricingSummary: { subtotalAmount?: number | string | null; discountAmount?: number | string | null; totalAmount?: number | string | null; marginPercent?: number | string | null; marginRisk: boolean; discountRisk: boolean; approvalRequired: boolean };
  approvalRequirements: Array<{ id: string; lineId?: string | null; requestType: string; severity: string; reasonCode: string; reason: string; status: string }>;
  auditTimeline: Array<{ action: string; occurredAt: string; metadata: string }>;
  reviewRequiredReasons: string[];
};

export type QuoteReviewCommandResult = {
  quoteId: string;
  previousStatus: string;
  newStatus: string;
  action: string;
  validationIssues: QuoteReviewDetail["validationIssues"];
  reviewRequiredReasons: string[];
  approvalRequired: boolean;
  validationSummary: string;
};

export type QuoteReviewCommandPayload = {
  reasonCode?: string;
  note?: string;
  quantity?: number;
  uom?: string;
  productId?: string;
  customerAccountId?: string;
  substituteProductId?: string;
  removeLine?: boolean;
  manualFollowUp?: boolean;
  fixType?: string;
  values?: Record<string, string>;
  idempotencyKey?: string;
};

export type QuoteConversionAttemptReviewFilter = {
  status?: string;
  reviewRequired?: boolean;
  reasonCode?: string;
  sourceChannel?: string;
  draftQuoteLinked?: boolean;
  createdFrom?: string;
  createdTo?: string;
};

export type QuoteConversionAttemptReviewItem = {
  id: string;
  sourceType: string;
  sourceChannel?: string | null;
  draftQuoteLinked: boolean;
  status: string;
  reviewRequired: boolean;
  reasonCode?: string | null;
  reasonCodes: string[];
  issueCount: number;
  customerResolution?: string | null;
  lineCount: number;
  requestMode?: string | null;
  triggeredByType?: string | null;
  createdAt: string;
};

export type QuoteValidationIssueDto = {
  code: string;
  severity: string;
  blocking: boolean;
  message: string;
  lineId?: string | null;
};

export type QuoteConversionAttemptReviewDetail = QuoteConversionAttemptReviewItem & {
  safeMetadata: Record<string, string | number | boolean | null>;
  validationIssues: QuoteValidationIssueDto[];
};

export function getQuoteReviewQueue(): Promise<ApiResult<QuoteReviewQueueRow[]>> {
  return coreApiGet<QuoteReviewQueueRow[]>("/api/v1/quote-review/queue");
}

export function getQuoteReviewDetail(quoteId: string): Promise<ApiResult<QuoteReviewDetail>> {
  return coreApiGet<QuoteReviewDetail>(`/api/v1/quote-review/${quoteId}`);
}

export function getQuoteConversionAttempts(filter: QuoteConversionAttemptReviewFilter = {}): Promise<ApiResult<QuoteConversionAttemptReviewItem[]>> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  const queryString = query.toString();
  return coreApiGet<QuoteConversionAttemptReviewItem[]>(`/api/v1/quote-review/conversion-attempts${queryString ? `?${queryString}` : ""}`);
}

export function getQuoteConversionAttemptDetail(attemptId: string): Promise<ApiResult<QuoteConversionAttemptReviewDetail>> {
  return coreApiGet<QuoteConversionAttemptReviewDetail>(`/api/v1/quote-review/conversion-attempts/${attemptId}`);
}

export function resolveQuoteReviewIssue(quoteId: string, issueId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(`/api/v1/quote-review/${quoteId}/issues/${issueId}/resolve`, payload);
}

export function escalateQuoteReviewIssue(quoteId: string, issueId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(`/api/v1/quote-review/${quoteId}/issues/${issueId}/escalate`, payload);
}

export function correctQuoteReviewLine(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(`/api/v1/quote-review/${quoteId}/lines/${lineId}/correct`, payload);
}

export function selectQuoteReviewSubstitute(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(`/api/v1/quote-review/${quoteId}/lines/${lineId}/substitutes/select`, payload);
}

export function rejectQuoteReviewSubstitute(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(`/api/v1/quote-review/${quoteId}/lines/${lineId}/substitutes/reject`, payload);
}

// OP-CAP-35: command helper attaches the HTTP status to the thrown error so
// callers can use mapOperatorActionError for status-specific safe messages.
// Non-200 responses are mapped to operator-safe messages — a 403/404 body (which
// may reference tenant/resource ids) is never surfaced into the UI.
async function requestQuoteReview<T>(path: string, payload: QuoteReviewCommandPayload): Promise<T> {
  const { idempotencyKey, ...body } = payload;
  const response = await fetch(`${coreApiBaseUrl()}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
      ...demoScopeHeaders()
    },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const error = Object.assign(
      new Error(coreApiStatusMessage(response.status)),
      { status: response.status }
    );
    throw error;
  }
  return response.json() as Promise<T>;
}
