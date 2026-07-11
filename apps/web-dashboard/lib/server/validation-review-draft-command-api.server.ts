import "server-only";

import type {
  ApiResult,
  ValidationReviewDraftStatus,
  ValidationReviewDraftabilityResponse
} from "../validation-review-draft-command-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type {
  ApiResult,
  ValidationReviewDraftStatus,
  ValidationReviewDraftabilityResponse
} from "../validation-review-draft-command-api.ts";

export async function getValidationReviewDraftStatus(
  validationRunId: string
): Promise<ApiResult<ValidationReviewDraftStatus>> {
  return tenantServerGetJsonNullable<ValidationReviewDraftStatus>(
    `/api/v1/validations/${validationRunId}/review/draft-status`
  );
}

export async function getValidationReviewDraftability(
  validationRunId: string
): Promise<ApiResult<ValidationReviewDraftabilityResponse>> {
  return tenantServerGetJsonNullable<ValidationReviewDraftabilityResponse>(
    `/api/v1/validations/${validationRunId}/review/draftability`
  );
}
