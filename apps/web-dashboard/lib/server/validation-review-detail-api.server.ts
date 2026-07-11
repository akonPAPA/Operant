import "server-only";

import type { ValidationReviewDetail } from "../validation-review-detail-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type { ValidationReviewDetail } from "../validation-review-detail-api.ts";

export function getValidationReviewByRun(validationRunId: string) {
  return tenantServerGetJsonNullable<ValidationReviewDetail>(
    `/api/v1/validations/${validationRunId}/review`
  );
}
