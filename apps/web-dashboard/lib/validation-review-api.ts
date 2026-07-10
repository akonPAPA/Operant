import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit, isDashboardApiAuthorityAvailable, usesBffTransport } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

export type ApiResult<T> = {
  data: T;
  error?: string;
};

export type ReviewCaseSummary = {
  id: string;
  caseNumber: string;
  validationRunId: string;
  extractionResultId: string;
  status: string;
  priority: string;
  severity: string;
  summary?: string;
  createdAt: string;
};

export type ExtractionSummary = {
  id: string;
  sourceType: string;
  sourceId: string;
  detectedIntent: string;
  documentType?: string;
  confidence?: number | string;
  validationStatus: string;
};

export type ValidationSummary = {
  id: string;
  status: string;
  overallStatus: string;
  confidence?: number | string;
  riskLevel: string;
  startedAt?: string;
  finishedAt?: string;
};

export type IssueReview = {
  id: string;
  extractedLineItemId?: string;
  extractedFieldId?: string;
  issueType: string;
  severity: string;
  status: string;
  message: string;
  suggestedAction?: string;
  riskLevel?: string;
};

export type IssueGroup = {
  group: string;
  issues: IssueReview[];
};

export type ApprovalRequirementReview = {
  id: string;
  extractedLineItemId?: string;
  requirementType: string;
  severity: string;
  status: string;
  reason: string;
  createdAt: string;
};

export type SubstituteCandidateReview = {
  id: string;
  extractedLineItemId: string;
  sourceProductId?: string;
  substituteProductId: string;
  substituteSku?: string;
  substituteName?: string;
  substituteType: string;
  riskLevel: string;
  rankScore?: number | string;
  requiresApproval: boolean;
  status: string;
  inventoryStatus?: string;
  marginStatus?: string;
  reason?: string;
};

export type FieldReview = {
  id: string;
  fieldName: string;
  rawValue?: string;
  normalizedValue?: string;
  confidence?: number | string;
  validationStatus: string;
};

export type LineItemReview = {
  id: string;
  lineNumber: number;
  rawSku?: string;
  rawDescription?: string;
  rawQuantity?: string;
  normalizedQuantity?: number | string;
  rawUom?: string;
  normalizedUom?: string;
  confidence?: number | string;
  validationStatus: string;
};

export type OperatorActionReview = {
  id: string;
  actionType: string;
  message: string;
  createdAt: string;
};

export type ValidationReviewCase = {
  reviewCase: ReviewCaseSummary;
  extraction: ExtractionSummary;
  validation: ValidationSummary;
  issueGroups: IssueGroup[];
  issueStatuses?: IssueStatusReview[];
  approvalRequirements: ApprovalRequirementReview[];
  pendingApprovals?: ApprovalRequirementReview[];
  rejectedApprovals?: ApprovalRequirementReview[];
  resolvedApprovals?: ApprovalRequirementReview[];
  productCandidates?: ProductCandidateReview[];
  substituteCandidates: SubstituteCandidateReview[];
  fields: FieldReview[];
  lineItems: LineItemReview[];
  timeline: OperatorActionReview[];
  correctionHistory?: OperatorActionReview[];
  draftPreparationAllowed?: boolean;
  blockingReasons?: BlockingReason[];
  readiness?: ReviewReadiness;
};

export type IssueStatusReview = {
  issueId: string;
  issueType: string;
  status: string;
  blocking: boolean;
  pendingApproval: boolean;
  lifecycleLabel: string;
};

export type ProductCandidateReview = {
  extractedLineItemId: string;
  productId: string;
  sku: string;
  name: string;
  matchType: string;
  confidence?: number | string;
  status: string;
};

export type BlockingReason = {
  issueCode: string;
  severity: string;
  reason: string;
  suggestedCorrectionAction?: string;
};

export type ReviewReadiness = {
  readinessStatus: string;
  draftPreparationAllowed: boolean;
  blockingReasons: BlockingReason[];
  pendingApprovals: ApprovalRequirementReview[];
  rejectedApprovals: ApprovalRequirementReview[];
  resolvedApprovals: ApprovalRequirementReview[];
  nextRequiredActions: string[];
};

export type ProductMatchResult = {
  extractedLineItemId: string;
  matchedProductId?: string;
  rawSku?: string;
  rawDescription?: string;
  matchType: string;
  confidence?: number | string;
  status: string;
  candidatesJson?: string;
};

export type UomNormalizationResult = {
  extractedLineItemId: string;
  rawUom?: string;
  normalizedUom?: string;
  status: string;
  confidence?: number | string;
};

export type InventoryCheckResult = {
  extractedLineItemId: string;
  productId?: string;
  locationId?: string;
  requestedQuantity?: number | string;
  quantityAvailable?: number | string;
  status: string;
};

export type PriceCheckResult = {
  extractedLineItemId: string;
  productId?: string;
  unitPrice?: number | string;
  currency?: string;
  status: string;
};

export type DiscountCheckResult = {
  extractedLineItemId?: string;
  requestedDiscountPercent?: number | string;
  maxAllowedDiscountPercent?: number | string;
  requiresApproval: boolean;
  status: string;
};

export type MarginCheckResult = {
  extractedLineItemId: string;
  unitPrice?: number | string;
  unitCost?: number | string;
  grossMarginPercent?: number | string;
  requiresApproval: boolean;
  status: string;
};

export type ValidationRunChecks = {
  productMatches: ProductMatchResult[];
  uomNormalizations: UomNormalizationResult[];
  inventoryChecks: InventoryCheckResult[];
  priceChecks: PriceCheckResult[];
  discountChecks: DiscountCheckResult[];
  marginChecks: MarginCheckResult[];
};

export type DraftPreparationResult = {
  id?: string;
  quoteNumber?: string;
  orderNumber?: string;
  status?: string;
  validationStatus?: string;
};

export type DraftPreview = {
  targetType: string;
  draftPreparationAllowed: boolean;
  blockingReasons: BlockingReason[];
  readiness?: ReviewReadiness;
  lines: DraftPreviewLine[];
  subtotal?: number | string;
  currency?: string;
  externalExecutionDisabled: boolean;
  inventoryReservationDisabled: boolean;
};

export type DraftPreviewLine = {
  extractedLineItemId: string;
  lineNumber: number;
  rawSku?: string;
  description?: string;
  quantity?: number | string;
  uom?: string;
  productId?: string;
  productSku?: string;
  productName?: string;
  substituteProductId?: string;
  substituteSku?: string;
  substituteName?: string;
  unitPrice?: number | string;
  currency?: string;
  marginPercent?: number | string;
  discountPercent?: number | string;
  stockStatus: string;
  priceStatus: string;
  marginStatus: string;
  validationStatus: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const validationReviewConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  if (usesBffTransport()) {
    return { "Content-Type": "application/json" };
  }
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (validationReviewConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = validationReviewConfig.tenantId;
  }
  return requestHeaders;
}

async function requestJson<T>(path: string, init?: RequestInit, fallbackData?: T): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(validationReviewConfig.tenantId)) {
    return { data: fallbackData as T, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await fetch(`${validationReviewConfig.baseUrl}${path}`, enrichDashboardRequestInit({
      cache: init?.method && init.method !== "GET" ? "no-store" : "no-store",
      ...init,
      headers: { ...headers(), ...(init?.headers ?? {}) }
    }));
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : (fallbackData as T);

    if (!response.ok) {
      const blocked = typeof data === "object" && data && "blockingReasons" in data ? (data as { blockingReasons?: BlockingReason[] }).blockingReasons ?? [] : [];
      const detail = blocked.map((reason) => `${reason.issueCode}: ${reason.reason}`).join(" ");
      const message = typeof data === "object" && data && "message" in data ? String((data as { message?: string }).message) : text;
      return { data, error: [message, detail].filter(Boolean).join(" ") || `Core API returned ${response.status}.` };
    }

    return { data };
  } catch (error) {
    return {
      data: fallbackData as T,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

export function listValidationReviewCases() {
  return requestJson<ReviewCaseSummary[]>("/api/v1/validation-review", { method: "GET" }, []);
}

export function createValidationReviewCase(extractionId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/extractions/${extractionId}/validation/review-case`, { method: "POST" });
}

export function getValidationReviewCase(reviewCaseId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}`, { method: "GET" });
}

export function approveValidationReviewCase(reviewCaseId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/approve`, { method: "POST", body: "{}" });
}

export function rejectValidationReviewCase(reviewCaseId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/reject`, { method: "POST", body: "{}" });
}

export function correctReviewUom(reviewCaseId: string, lineItemId: string, normalizedUom: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/corrections/uom`, {
    method: "POST",
    body: JSON.stringify({ lineItemId, normalizedUom })
  });
}

export function correctReviewQuantity(reviewCaseId: string, lineItemId: string, normalizedQuantity: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/corrections/quantity`, {
    method: "POST",
    body: JSON.stringify({ lineItemId, normalizedQuantity })
  });
}

export function mapReviewProduct(reviewCaseId: string, lineItemId: string, productId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/corrections/product`, {
    method: "POST",
    body: JSON.stringify({ lineItemId, productId })
  });
}

export function selectReviewSubstitute(reviewCaseId: string, candidateId: string, reason: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/substitutes/select`, {
    method: "POST",
    body: JSON.stringify({ candidateId, reason })
  });
}

export function rejectReviewSubstitute(reviewCaseId: string, candidateId: string, reason: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/substitutes/reject`, {
    method: "POST",
    body: JSON.stringify({ candidateId, reason })
  });
}

export function acknowledgeReviewIssue(reviewCaseId: string, issueId: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/issues/acknowledge`, {
    method: "POST",
    body: JSON.stringify({ issueId })
  });
}

export function overrideReviewIssue(reviewCaseId: string, issueId: string, reason: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/issues/override`, {
    method: "POST",
    body: JSON.stringify({ issueId, reason })
  });
}

export function approveReviewApproval(reviewCaseId: string, approvalRequestId: string, reason: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/approvals/${approvalRequestId}/approve`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export function rejectReviewApproval(reviewCaseId: string, approvalRequestId: string, reason: string) {
  return requestJson<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}/approvals/${approvalRequestId}/reject`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export function prepareDraftQuote(reviewCaseId: string) {
  return requestJson<DraftPreparationResult>(`/api/v1/validation-review/${reviewCaseId}/prepare-draft-quote`, { method: "POST", body: "{}" });
}

export function prepareDraftOrder(reviewCaseId: string) {
  return requestJson<DraftPreparationResult>(`/api/v1/validation-review/${reviewCaseId}/prepare-draft-order`, { method: "POST", body: "{}" });
}

export function getDraftPreview(reviewCaseId: string, targetType = "QUOTE") {
  return requestJson<DraftPreview>(`/api/v1/validation-review/${reviewCaseId}/draft-preview?targetType=${encodeURIComponent(targetType)}`, { method: "GET" });
}

async function getRunCheck<T>(validationRunId: string, suffix: string) {
  return requestJson<T[]>(`/api/v1/validations/runs/${validationRunId}/${suffix}`, { method: "GET" }, []);
}

export async function getValidationRunChecks(validationRunId: string): Promise<ApiResult<ValidationRunChecks>> {
  const [productMatches, uomNormalizations, inventoryChecks, priceChecks, discountChecks, marginChecks] = await Promise.all([
    getRunCheck<ProductMatchResult>(validationRunId, "product-matches"),
    getRunCheck<UomNormalizationResult>(validationRunId, "uom-normalizations"),
    getRunCheck<InventoryCheckResult>(validationRunId, "inventory-checks"),
    getRunCheck<PriceCheckResult>(validationRunId, "price-checks"),
    getRunCheck<DiscountCheckResult>(validationRunId, "discount-checks"),
    getRunCheck<MarginCheckResult>(validationRunId, "margin-checks")
  ]);

  return {
    data: {
      productMatches: productMatches.data,
      uomNormalizations: uomNormalizations.data,
      inventoryChecks: inventoryChecks.data,
      priceChecks: priceChecks.data,
      discountChecks: discountChecks.data,
      marginChecks: marginChecks.data
    },
    error: [productMatches.error, uomNormalizations.error, inventoryChecks.error, priceChecks.error, discountChecks.error, marginChecks.error].filter(Boolean).join(" ")
  };
}
