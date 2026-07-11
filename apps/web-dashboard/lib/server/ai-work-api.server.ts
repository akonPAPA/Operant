import "server-only";

import type { AiWorkSuggestion } from "../ai-work-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type { AiWorkSuggestion } from "../ai-work-api.ts";

export function listRecentAiWork(limit = 50) {
  return tenantServerGetJson<AiWorkSuggestion[]>(
    `/api/v1/ai-work/suggestions?limit=${encodeURIComponent(limit)}`
  );
}
