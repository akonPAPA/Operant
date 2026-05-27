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
  auditCorrelationId: string;
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

export type CreateDraftQuoteFromRfqPayload = {
  tenantId: string;
  actorRole: string;
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

const baseUrl = process.env.NEXT_PUBLIC_CORE_API_URL ?? process.env.CORE_API_BASE_URL ?? DEFAULT_BASE_URL;

export async function createDraftQuoteFromRfq(payload: CreateDraftQuoteFromRfqPayload): Promise<QuoteTransactionResponse> {
  const response = await fetch(`${baseUrl}/api/v1/quotes/from-rfq`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Tenant-Id": payload.tenantId
    },
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    throw new Error(`Core API returned ${response.status}`);
  }
  return response.json() as Promise<QuoteTransactionResponse>;
}
