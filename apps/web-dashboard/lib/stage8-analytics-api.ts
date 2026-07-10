import { dashboardCoreApiBaseUrl } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

export type Stage8CommandCenterAnalytics = {
  totalInboundRequests: number;
  botOnlyHandoffCount: number;
  validationBackedReviewCount: number;
  blockedUnsafeDraftAttempts: number;
  exceptionRate: number | string;
  automationRate: number | string;
  draftsPrepared: number;
  channelMix: Record<string, number>;
};

export type Stage8ReconciliationSummary = {
  inventoryMismatchCount: number;
  highSeverityDiscrepancyCount: number;
  staleInventoryCount: number;
  lowStockCount: number;
  openReconciliationCases: number;
  movementMirrorCount: number;
  unsupportedMovementTypes: string[];
};

export type ReconciliationCase = {
  id: string;
  productId: string;
  locationId: string;
  expectedStock: number | string;
  actualStock: number | string;
  mismatchQuantity: number | string;
  severity: string;
  status: string;
  likelyCauses: string;
};

export type ReconciliationCasesResponse = {
  cases: ReconciliationCase[];
  totalElements: number;
};

export type Stage8ProductTimeline = {
  productId: string;
  movements: Array<{
    id: string;
    productId: string;
    locationId: string;
    movementType: string;
    quantity: number | string;
    occurredAt: string;
    sourceType: string;
    sourceReference?: string;
  }>;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const stage8AnalyticsConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

export async function getStage8CommandCenterAnalytics(): Promise<Stage8CommandCenterAnalytics | null> {
  if (!stage8AnalyticsConfig.tenantId) return null;
  try {
    const response = await fetch(`${stage8AnalyticsConfig.baseUrl}/api/stage8/analytics/command-center`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": stage8AnalyticsConfig.tenantId }
    });
    if (!response.ok) return null;
    return (await response.json()) as Stage8CommandCenterAnalytics;
  } catch {
    return null;
  }
}

export async function getStage8ReconciliationSummary(): Promise<Stage8ReconciliationSummary | null> {
  return requestStage8<Stage8ReconciliationSummary>("/api/stage8/reconciliation/summary");
}

export async function getStage8ReconciliationCases(): Promise<ReconciliationCasesResponse | null> {
  return requestStage8<ReconciliationCasesResponse>("/api/stage8/reconciliation/cases");
}

export async function getStage8ProductTimeline(productId: string): Promise<Stage8ProductTimeline | null> {
  return requestStage8<Stage8ProductTimeline>(`/api/stage8/reconciliation/products/${productId}/timeline`);
}

async function requestStage8<T>(path: string): Promise<T | null> {
  if (!stage8AnalyticsConfig.tenantId) return null;
  try {
    const response = await fetch(`${stage8AnalyticsConfig.baseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": stage8AnalyticsConfig.tenantId }
    });
    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    return null;
  }
}
