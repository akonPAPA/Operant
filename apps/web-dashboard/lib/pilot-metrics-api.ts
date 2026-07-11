import { dashboardCoreApiBaseUrl, dashboardRequestHeaders, isDashboardApiAuthorityAvailable } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

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

export type PilotReadinessSignal = {
  label: string;
  value: string;
  assessment: string;
};

// OP-CAP-11G evidence report pack: structured, non-raw composition of pilot metrics.
export type PilotEvidenceReport = {
  reportGeneratedAt: string;
  tenantId: string;
  totalShadowRuns: number;
  totalHumanCorrections: number;
  averageManualBaselineMinutes: number;
  averageAssistedProcessingMinutes: number;
  estimatedMinutesSaved: number;
  estimatedCostSaved: number;
  currency: string;
  automationCandidateCount: number;
  reviewRequiredCount: number;
  humanCorrectionRate: number;
  exceptionBreakdown: ExceptionCategory[];
  topExceptionCategories: ExceptionCategory[];
  readinessSignals: PilotReadinessSignal[];
  limitations: string[];
  safetyStatement: string;
};

// OP-CAP-11H demo scenario pack: read-only, honest demo-readiness composition.
export type PilotDemoScenarioCapability = {
  name: string;
  available: boolean;
  note: string;
};

export type PilotDemoScenarioEvidence = {
  label: string;
  value: string;
};

export type PilotDemoScenarioSafetyBoundary = {
  statement: string;
};

export type PilotDemoScenario = {
  code: string;
  title: string;
  businessObjective: string;
  primaryActorRole: string;
  channelSourceType: string;
  readiness: string;
  readinessScore: number;
  requiredCapabilities: PilotDemoScenarioCapability[];
  evidenceSignals: PilotDemoScenarioEvidence[];
  missingCapabilities: string[];
  safetyBoundaries: PilotDemoScenarioSafetyBoundary[];
  suggestedDemoRoute: string;
  relatedReportLinks: string[];
  operatorTalkingPoints: string[];
};

export type PilotDemoScenarioPack = {
  reportGeneratedAt: string;
  tenantId: string;
  tenantHasPilotEvidence: boolean;
  scenarios: PilotDemoScenario[];
  packLimitations: string[];
  safetyStatement: string;
};

export type PilotApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const pilotMetricsClient = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function baseHeaders(): Record<string, string> {
  return dashboardRequestHeaders(pilotMetricsClient.tenantId, ANALYTICS_READ);
}

async function read<T>(path: string, fallback: T): Promise<PilotApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(pilotMetricsClient.tenantId)) {
    return { data: fallback, error: "Authenticated dashboard access is unavailable." };
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

const EMPTY_EVIDENCE_REPORT: PilotEvidenceReport = {
  reportGeneratedAt: "",
  tenantId: "",
  totalShadowRuns: 0,
  totalHumanCorrections: 0,
  averageManualBaselineMinutes: 0,
  averageAssistedProcessingMinutes: 0,
  estimatedMinutesSaved: 0,
  estimatedCostSaved: 0,
  currency: "USD",
  automationCandidateCount: 0,
  reviewRequiredCount: 0,
  humanCorrectionRate: 0,
  exceptionBreakdown: [],
  topExceptionCategories: [],
  readinessSignals: [],
  limitations: [],
  safetyStatement: ""
};

export function getPilotEvidenceReport() {
  return read<PilotEvidenceReport>("/api/v1/pilot/evidence-report", EMPTY_EVIDENCE_REPORT);
}

const EMPTY_DEMO_SCENARIO_PACK: PilotDemoScenarioPack = {
  reportGeneratedAt: "",
  tenantId: "",
  tenantHasPilotEvidence: false,
  scenarios: [],
  packLimitations: [],
  safetyStatement: ""
};

export function getPilotDemoScenarios() {
  return read<PilotDemoScenarioPack>("/api/v1/pilot/demo-scenarios", EMPTY_DEMO_SCENARIO_PACK);
}
