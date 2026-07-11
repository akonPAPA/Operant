import "server-only";

import type {
  ApiResult,
  DiscountCheckResult,
  DraftPreview,
  InventoryCheckResult,
  MarginCheckResult,
  PriceCheckResult,
  ProductMatchResult,
  ReviewCaseSummary,
  UomNormalizationResult,
  ValidationReviewCase,
  ValidationRunChecks
} from "../validation-review-api.ts";
import { tenantServerGetJson, tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

function asApiResult<T>(result: { data: T | null; error?: string }): ApiResult<T | undefined> {
  return { data: result.data ?? undefined, error: result.error };
}

export type {
  ApiResult,
  DraftPreview,
  ReviewCaseSummary,
  ValidationReviewCase,
  ValidationRunChecks
} from "../validation-review-api.ts";

export async function listValidationReviewCases(): Promise<ApiResult<ReviewCaseSummary[]>> {
  const result = await tenantServerGetJson<ReviewCaseSummary[]>("/api/v1/validation-review");
  return { data: result.data, error: result.error };
}

export async function getValidationReviewCase(reviewCaseId: string): Promise<ApiResult<ValidationReviewCase | undefined>> {
  return asApiResult(
    await tenantServerGetJsonNullable<ValidationReviewCase>(`/api/v1/validation-review/${reviewCaseId}`)
  );
}

export async function getDraftPreview(
  reviewCaseId: string,
  targetType = "QUOTE"
): Promise<ApiResult<DraftPreview | undefined>> {
  return asApiResult(
    await tenantServerGetJsonNullable<DraftPreview>(
      `/api/v1/validation-review/${reviewCaseId}/draft-preview?targetType=${encodeURIComponent(targetType)}`
    )
  );
}

async function getRunCheck<T>(validationRunId: string, suffix: string) {
  return tenantServerGetJson<T[]>(`/api/v1/validations/runs/${validationRunId}/${suffix}`);
}

export async function getValidationRunChecks(
  validationRunId: string
): Promise<ApiResult<ValidationRunChecks>> {
  const [productMatches, uomNormalizations, inventoryChecks, priceChecks, discountChecks, marginChecks] =
    await Promise.all([
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
    error: [
      productMatches.error,
      uomNormalizations.error,
      inventoryChecks.error,
      priceChecks.error,
      discountChecks.error,
      marginChecks.error
    ]
      .filter(Boolean)
      .join(" ")
      .trim() || undefined
  };
}
