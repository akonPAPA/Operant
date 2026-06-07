// OP-CAP-11F Pilot Shadow-Mode ROI Readiness client.
// Read-only tenant-scoped pilot evidence: shadow-mode summary + exception breakdown.
// Reads require ANALYTICS_READ (re-validated by the backend ApiPermissionInterceptor on
// every request). These responses are advisory only — they never approve quotes/orders and
// never trigger external/ERP/connector writes. No raw payloads, AI output, or secrets are
// exposed: payload presence is surfaced as a boolean flag by the backend.

export type PilotMetrics = {
  totalShadowRuns: number;
  reviewedShadowRuns: number;
  acceptedCount: number;
  correctedCount: number;
  rejectedCount: number;
  humanCorrectionRate: number;
  averageConfidence: number;
  exceptionCategoryCounts: Record<string, number>;
  predictionTypeBreakdown: Record<string, number>;
  correctionTypeBreakdown: Record<string, number>;
  automationCandidateCount: number;
  reviewRequiredCount: number;
  averageManualBaselineMinutes: number;
  averageAssistedMinutes: number;
  estimatedMinutesSaved: number;
  estimatedCostSaved: number;
  costCurrency: string;
};

export type ExceptionCategory = {
  category: string;
  count: number;
  percentage: number;
};

export type PilotExceptionBreakdown = {
  totalCategorized: number;
  categories: ExceptionCategory[];
};

export type PilotApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const pilotMetricsClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-OrderPilot-Permissions": ANALYTICS_READ
  };
  if (pilotMetricsClient.tenantId) {
    h["X-Tenant-Id"] = pilotMetricsClient.tenantId;
  }
  return h;
}

async function read<T>(path: string, fallback: T): Promise<PilotApiResult<T>> {
  if (!pilotMetricsClient.tenantId) {
    return { data: fallback, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped pilot metrics." };
  }
  try {
    const response = await fetch(`${pilotMetricsClient.baseUrl}${path}`, {
      cache: "no-store",
      method: "GET",
      headers: baseHeaders()
    });
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : fallback;
    if (!response.ok) {
      const message =
        typeof data === "object" && data && "message" in data
          ? String((data as { message?: string }).message)
          : "";
      return { data: fallback, error: message || `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: fallback, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

const EMPTY_METRICS: PilotMetrics = {
  totalShadowRuns: 0,
  reviewedShadowRuns: 0,
  acceptedCount: 0,
  correctedCount: 0,
  rejectedCount: 0,
  humanCorrectionRate: 0,
  averageConfidence: 0,
  exceptionCategoryCounts: {},
  predictionTypeBreakdown: {},
  correctionTypeBreakdown: {},
  automationCandidateCount: 0,
  reviewRequiredCount: 0,
  averageManualBaselineMinutes: 0,
  averageAssistedMinutes: 0,
  estimatedMinutesSaved: 0,
  estimatedCostSaved: 0,
  costCurrency: "USD"
};

export function getPilotMetrics() {
  return read<PilotMetrics>("/api/v1/pilot/metrics", EMPTY_METRICS);
}

export function getPilotExceptionBreakdown() {
  return read<PilotExceptionBreakdown>("/api/v1/pilot/metrics/exceptions", { totalCategorized: 0, categories: [] });
}
