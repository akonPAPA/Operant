import "server-only";

import type { CommerceIntelligenceDemoFlow, CommerceIntelligenceResult } from "../commerce-intelligence-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type { CommerceIntelligenceDemoFlow, CommerceIntelligenceResult } from "../commerce-intelligence-api.ts";

export async function getCommerceIntelligenceDemoFlow(): Promise<CommerceIntelligenceResult> {
  return tenantServerGetJsonNullable<CommerceIntelligenceDemoFlow>(
    "/api/v1/commerce-intelligence/demo-flow"
  );
}
