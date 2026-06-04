const DEFAULT_BASE_URL = "http://localhost:8080";

export type QuoteReviewQueueRow = {
  quoteId: string;
  conversionAttemptId?: string | null;
  sourceType?: string | null;
  sourceId?: string | null;
  sourceChannel?: string | null;
  customer: { customerAccountId?: string | null; displayName?: string | null; resolutionStatus: string };
  lineCount: number;
  validationIssueCount: number;
  highestSeverity: string;
  status: string;
  createdAt: string;
  assignedOperatorId?: string | null;
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
    auditCorrelationId?: string | null;
  };
  status: string;
  sourceContext?: {
    sourceType: string;
    sourceId: string;
    sourceChannel?: string | null;
    sourceExternalRef?: string | null;
    sourceReceivedAt?: string | null;
    conversionAttemptId?: string | null;
    conversionStatus?: string | null;
    candidateLines: Array<{ sourceLineItemId?: string | null; lineNumber: number; rawSkuOrAlias?: string | null; description?: string | null; quantity?: number | string | null; uom?: string | null; status?: string | null }>;
    validationIssues: Array<{ code: string; severity: string; blocking: boolean; message: string; lineId?: string | null }>;
  } | null;
  conversionAttempt?: {
    id: string;
    sourceType: string;
    sourceId: string;
    status: string;
    failureCode?: string | null;
    failureMessage?: string | null;
    requestMode: string;
    triggeredBy?: string | null;
    triggeredByType: string;
  } | null;
  sourceLines: Array<{ sourceLineItemId?: string | null; lineNumber: number; rawSkuOrAlias?: string | null; description?: string | null; quantity?: number | string | null; uom?: string | null; status?: string | null }>;
  draftQuoteLines: Array<{ id: string; lineNumber: number; rawSkuOrAlias?: string | null; productId?: string | null; productName?: string | null; quantity: number | string; uom: string; unitPrice?: number | string | null; marginPercent?: number | string | null; validationStatus: string }>;
  validationIssues: Array<{ id: string; lineId?: string | null; issueCode: string; severity: string; blocking: boolean; message: string; status: string }>;
  proposedSubstitutes: Array<{ lineId?: string | null; productId: string; sku: string; productName: string; riskLevel: string; reasonCode: string; availableStock?: number | string | null; stockStatus: string; requiresApproval: boolean; blocked: boolean; explanation: string }>;
  pricingSummary: { subtotalAmount?: number | string | null; discountAmount?: number | string | null; totalAmount?: number | string | null; marginPercent?: number | string | null; marginRisk: boolean; discountRisk: boolean; approvalRequired: boolean };
  approvalRequirements: Array<{ id: string; lineId?: string | null; requestType: string; severity: string; reasonCode: string; reason: string; status: string }>;
  auditTimeline: Array<{ id: string; action: string; entityType: string; entityId: string; actorId?: string | null; occurredAt: string; metadata: string }>;
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
  tenantId: string;
  actorId?: string;
  actorRole?: string;
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
  sourceId: string;
  sourceChannel?: string | null;
  channelMessageId?: string | null;
  inboundDocumentId?: string | null;
  draftQuoteId?: string | null;
  draftQuoteLinked: boolean;
  status: string;
  reviewRequired: boolean;
  reasonCode?: string | null;
  reasonCodes: string[];
  issueCount: number;
  customerResolution?: string | null;
  lineCount: number;
  requestMode?: string | null;
  triggeredBy?: string | null;
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

const baseUrl = process.env.NEXT_PUBLIC_CORE_API_URL ?? process.env.CORE_API_BASE_URL ?? DEFAULT_BASE_URL;

export function getQuoteReviewQueue(tenantId: string): Promise<QuoteReviewQueueRow[]> {
  return requestQuoteReview<QuoteReviewQueueRow[]>(tenantId, "/api/v1/quote-review/queue");
}

export function getQuoteReviewDetail(tenantId: string, quoteId: string): Promise<QuoteReviewDetail> {
  return requestQuoteReview<QuoteReviewDetail>(tenantId, `/api/v1/quote-review/${quoteId}`);
}

export function getQuoteConversionAttempts(tenantId: string, filter: QuoteConversionAttemptReviewFilter = {}): Promise<QuoteConversionAttemptReviewItem[]> {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  const queryString = query.toString();
  return requestQuoteReview<QuoteConversionAttemptReviewItem[]>(tenantId, `/api/v1/quote-review/conversion-attempts${queryString ? `?${queryString}` : ""}`);
}

export function getQuoteConversionAttemptDetail(tenantId: string, attemptId: string): Promise<QuoteConversionAttemptReviewDetail> {
  return requestQuoteReview<QuoteConversionAttemptReviewDetail>(tenantId, `/api/v1/quote-review/conversion-attempts/${attemptId}`);
}

export function resolveQuoteReviewIssue(quoteId: string, issueId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(payload.tenantId, `/api/v1/quote-review/${quoteId}/issues/${issueId}/resolve`, payload);
}

export function escalateQuoteReviewIssue(quoteId: string, issueId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(payload.tenantId, `/api/v1/quote-review/${quoteId}/issues/${issueId}/escalate`, payload);
}

export function correctQuoteReviewLine(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(payload.tenantId, `/api/v1/quote-review/${quoteId}/lines/${lineId}/correct`, payload);
}

export function selectQuoteReviewSubstitute(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(payload.tenantId, `/api/v1/quote-review/${quoteId}/lines/${lineId}/substitutes/select`, payload);
}

export function rejectQuoteReviewSubstitute(quoteId: string, lineId: string, payload: QuoteReviewCommandPayload): Promise<QuoteReviewCommandResult> {
  return requestQuoteReview<QuoteReviewCommandResult>(payload.tenantId, `/api/v1/quote-review/${quoteId}/lines/${lineId}/substitutes/reject`, payload);
}

async function requestQuoteReview<T>(tenantId: string, path: string, payload?: QuoteReviewCommandPayload): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: payload ? "POST" : "GET",
    headers: {
      "Content-Type": "application/json",
      "X-Tenant-Id": tenantId
    },
    body: payload ? JSON.stringify(payload) : undefined
  });
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Core API returned ${response.status}`);
  }
  return response.json() as Promise<T>;
}
