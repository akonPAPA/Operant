const DEFAULT_BASE_URL = "http://localhost:8080";

export const stage8ValueConfig = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

export type RoiAssumptions = {
  averageManualHandlingMinutesPerRequest: number | string;
  averageFullyLoadedOperatorHourlyCost: number | string;
  defaultCurrency: string;
  valueAttributionMode: string;
  defaultAssumptions: boolean;
};

export type Stage8ValueSummary = {
  estimatedOperatorHoursSaved: number | string;
  estimatedLaborCostSaved: number | string;
  averageReviewCycleHours: number | string;
  averageDraftPreparationCycleHours: number | string;
  blockedUnsafeDraftAttempts: number;
  discountLeakageCount: number;
  estimatedDiscountLeakageValue: number | string;
  marginRiskCount: number;
  estimatedMarginRiskImpact: number | string;
  substituteRecoveredRevenue: number | string;
  inventoryDiscrepancyValue: number | string;
  staleInventoryRiskCount: number;
  currency: string;
  estimated: boolean;
  defaultAssumptions: boolean;
};

export type Stage8PilotRoiReport = {
  totalInboundRequests: number;
  automationRate: number | string;
  exceptionRate: number | string;
  botHandoffs: number;
  draftQuoteCount: number;
  draftOrderCount: number;
  blockedUnsafeAttempts: number;
  estimatedHoursSaved: number | string;
  estimatedLaborCostSaved: number | string;
  marginRiskCount: number;
  discountLeakageCount: number;
  inventoryDiscrepancyValue: number | string;
  topExceptionCategories: Record<string, number>;
  topReconciliationIssues: Record<string, number>;
  assumptions: RoiAssumptions;
  exportable: boolean;
};

export async function getStage8ValueSummary(): Promise<Stage8ValueSummary | null> {
  return requestStage8Value<Stage8ValueSummary>("/api/stage8/value/summary");
}

export async function getStage8RoiAssumptions(): Promise<RoiAssumptions | null> {
  return requestStage8Value<RoiAssumptions>("/api/stage8/value/roi-assumptions");
}

export function getStage8PilotReportExportUrl(): string | null {
  if (!stage8ValueConfig.tenantId) return null;
  return `${stage8ValueConfig.baseUrl}/api/stage8/value/export`;
}

async function requestStage8Value<T>(path: string): Promise<T | null> {
  if (!stage8ValueConfig.tenantId) return null;
  try {
    const response = await fetch(`${stage8ValueConfig.baseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": stage8ValueConfig.tenantId }
    });
    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    return null;
  }
}
