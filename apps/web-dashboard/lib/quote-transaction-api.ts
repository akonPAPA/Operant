import { enrichDashboardRequestInit } from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { requireDemoTenantId } from "./frontend-authority.mjs";
import { BoundedUiError, uiErrorForStatus } from "./ui-error.ts";

const DEFAULT_BASE_URL = "http://localhost:8080";

export type QuoteTransactionResponse = {
  draftQuoteId: string;
  status: string;
  resolvedCustomer?: {
    id: string;
    externalRef?: string;
    accountCode?: string;
    displayName: string;
    status: string;
  } | null;
  lines: Array<{
    id: string;
    lineNumber: number;
    rawSkuOrAlias?: string;
    normalizedSku?: string;
    productId?: string;
    productName?: string;
    quantity: number | string;
    uom: string;
    unitPrice?: number | string;
    discountPercent?: number | string;
    lineTotal?: number | string;
    marginPercent?: number | string;
    availableStock?: number | string;
    validationStatus: string;
  }>;
  validationIssues: Array<{
    id: string;
    lineId?: string;
    issueCode: string;
    severity: string;
    blocking: boolean;
    message: string;
    status: string;
  }>;
  substituteCandidates: Array<{
    lineId?: string;
    productId: string;
    sku: string;
    productName: string;
    riskLevel: string;
    reasonCode: string;
    availableStock?: number | string;
    stockStatus: string;
    requiresApproval: boolean;
    blocked: boolean;
    explanation: string;
  }>;
  approvalRequired: boolean;
  approvalReasons: string[];
  approvalRequests: Array<{
    id: string;
    lineId?: string;
    requestType: string;
    severity: string;
    reasonCode: string;
    reason: string;
    status: string;
  }>;
};

export type QuoteApprovalState = {
  quoteId: string;
  status: string;
  approvalRequired: boolean;
  blockingIssues: QuoteTransactionResponse["validationIssues"];
  approvalReasons: string[];
  approvalRequests: QuoteTransactionResponse["approvalRequests"];
  approvalDecision?: {
    id: string;
    approvalRequestId?: string;
    decision: string;
    comment?: string;
    decidedAt: string;
    previousQuoteStatus: string;
    newQuoteStatus: string;
  } | null;
  internalDraftOrderId?: string | null;
  changeRequestId?: string | null;
  externalExecutionEnabled: boolean;
};

export type QuoteApprovalCommandResponse = {
  quoteId: string;
  previousStatus: string;
  newStatus: string;
  approvalRequired: boolean;
  approvalDecision: "APPROVE" | "REJECT" | "REQUEST_CHANGES" | "CONVERT" | string;
  blockingIssues: QuoteTransactionResponse["validationIssues"];
  approvalReasons: string[];
  internalDraftOrderId?: string | null;
  changeRequestId?: string | null;
  externalExecutionEnabled: boolean;
};

// OP-CAP-32: operator-safe conversion result. The backend deliberately withholds internal
// identifiers — conversionAttemptId is @JsonIgnore'd, and sourceId/auditEventIds are not part of the
// response DTO — so the frontend contract must not declare or render them.
export type ChannelToQuoteResponse = {
  status: string;
  quoteId?: string | null;
  sourceType: string;
  customerResolution?: string | null;
  lineCount: number;
  acceptedLineCount: number;
  validationIssues: Array<{
    code: string;
    severity: string;
    blocking: boolean;
    message: string;
    lineId?: string | null;
  }>;
  reviewRequired: boolean;
};

// OP-CAP-31: operator-safe source summary. Raw internal identifiers (sourceId, conversionAttemptId,
// triggeredBy/createdBy actor) are no longer part of the contract.
export type QuoteSourceContext = {
  sourceType: string;
  sourceChannel?: string | null;
  sourceExternalRef?: string | null;
  sourceReceivedAt?: string | null;
  conversionStatus?: string | null;
  candidateLineCount: number;
  reviewRequired: boolean;
  validationIssues: ChannelToQuoteResponse["validationIssues"];
};

// OP-CAP-31: business intent only. Tenant is sent via the X-Tenant-Id header and actor is resolved
// by the backend from trusted context — never in the JSON body.
export type ChannelToQuotePayload = {
  idempotencyKey?: string;
  requestedCustomerAccountId?: string;
  requestedQuoteType?: string;
  operatorNotes?: string;
  dryRun?: boolean;
  forceReview?: boolean;
  selectedLineItemIds?: string[];
  selectedSubstituteIds?: Record<string, string>;
};

// OP-CAP-31: the tenant header is resolved by the explicit local demo authority boundary.
export type CreateDraftQuoteFromRfqPayload = {
  customerExternalRef: string;
  requestedLocation: string;
  requestedDiscountPercent: number;
  idempotencyKey: string;
  requestedItems: Array<{
    rawSkuOrAlias: string;
    description: string;
    quantity: number;
    uom: string;
  }>;
};

// OP-CAP-31: the body carries business intent only (approvalRequestId/reason/comment);
// tenant/actor/role are not caller-provided authority.
export type QuoteApprovalDecisionPayload = {
  approvalRequestId?: string;
  reason?: string;
  comment?: string;
  idempotencyKey?: string;
};

export async function createDraftQuoteFromRfq(payload: CreateDraftQuoteFromRfqPayload): Promise<QuoteTransactionResponse> {
  const { idempotencyKey, ...body } = payload;
  const response = await dashboardApiFetch(
    "/api/v1/quotes/from-rfq",
    enrichDashboardRequestInit({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Tenant-Id": requireDemoTenantId(),
        "Idempotency-Key": idempotencyKey
      },
      body: JSON.stringify(body)
    })
  );
  if (!response.ok) {
    const mapped = uiErrorForStatus(response.status);
    throw new BoundedUiError(mapped.message, response.status);
  }
  return response.json() as Promise<QuoteTransactionResponse>;
}

export function getQuoteApprovalState(quoteId: string): Promise<QuoteApprovalState> {
  return requestQuoteApproval<QuoteApprovalState>(`/api/v1/quotes/${quoteId}/approval-state`);
}

export function approveQuote(quoteId: string, payload: QuoteApprovalDecisionPayload): Promise<QuoteApprovalCommandResponse> {
  return requestQuoteApproval<QuoteApprovalCommandResponse>(`/api/v1/quotes/${quoteId}/approve`, payload);
}

export function rejectQuote(quoteId: string, payload: QuoteApprovalDecisionPayload): Promise<QuoteApprovalCommandResponse> {
  return requestQuoteApproval<QuoteApprovalCommandResponse>(`/api/v1/quotes/${quoteId}/reject`, payload);
}

export function requestQuoteChanges(quoteId: string, payload: QuoteApprovalDecisionPayload): Promise<QuoteApprovalCommandResponse> {
  return requestQuoteApproval<QuoteApprovalCommandResponse>(`/api/v1/quotes/${quoteId}/request-changes`, payload);
}

export function convertQuoteToInternalOrder(quoteId: string, payload: QuoteApprovalDecisionPayload): Promise<QuoteApprovalCommandResponse> {
  return requestQuoteApproval<QuoteApprovalCommandResponse>(`/api/v1/quotes/${quoteId}/convert-to-internal-order`, payload);
}

export function createQuoteFromChannelMessage(messageId: string, payload: ChannelToQuotePayload): Promise<ChannelToQuoteResponse> {
  return requestQuoteTransaction<ChannelToQuoteResponse>(`/api/v1/quote-transactions/from-channel-message/${messageId}`, payload);
}

export function createQuoteFromInboundDocument(documentId: string, payload: ChannelToQuotePayload): Promise<ChannelToQuoteResponse> {
  return requestQuoteTransaction<ChannelToQuoteResponse>(`/api/v1/quote-transactions/from-inbound-document/${documentId}`, payload);
}

export function getQuoteSourceContext(quoteId: string): Promise<QuoteSourceContext> {
  return requestQuoteApproval<QuoteSourceContext>(`/api/v1/quotes/${quoteId}/source-context`);
}

async function requestQuoteApproval<T>(path: string, payload?: QuoteApprovalDecisionPayload): Promise<T> {
  // OP-CAP-31: tenant comes from the explicit local demo resolver. The body carries business
  // intent only, and the idempotency key is never serialized into the JSON body.
  const idempotencyKey = payload?.idempotencyKey;
  const body = payload
    ? { approvalRequestId: payload.approvalRequestId, reason: payload.reason, comment: payload.comment }
    : undefined;
  const response = await dashboardApiFetch(
    path,
    enrichDashboardRequestInit({
      method: payload ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        "X-Tenant-Id": requireDemoTenantId(),
        ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {})
      },
      body: payload ? JSON.stringify(body) : undefined
    })
  );
  if (!response.ok) {
    const mapped = uiErrorForStatus(response.status);
    throw new BoundedUiError(mapped.message, response.status);
  }
  return response.json() as Promise<T>;
}

async function requestQuoteTransaction<T>(path: string, payload: ChannelToQuotePayload): Promise<T> {
  const { idempotencyKey, ...body } = payload;
  const response = await dashboardApiFetch(
    path,
    enrichDashboardRequestInit({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Tenant-Id": requireDemoTenantId(),
        ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {})
      },
      body: JSON.stringify(body)
    })
  );
  if (!response.ok) {
    const mapped = uiErrorForStatus(response.status);
    throw new BoundedUiError(mapped.message, response.status);
  }
  return response.json() as Promise<T>;
}
