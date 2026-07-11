import "server-only";

import type {
  PilotDemoScenarioPack,
  PilotEvidenceReport,
  PilotExceptionBreakdown,
  PilotMetrics
} from "../pilot-metrics-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type {
  PilotDemoScenarioPack,
  PilotEvidenceReport,
  PilotExceptionBreakdown,
  PilotMetrics
} from "../pilot-metrics-api.ts";

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

const EMPTY_EXCEPTIONS: PilotExceptionBreakdown = { totalCategorized: 0, categories: [] };

const EMPTY_EVIDENCE: PilotEvidenceReport = {
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

const EMPTY_DEMO_PACK: PilotDemoScenarioPack = {
  reportGeneratedAt: "",
  tenantId: "",
  tenantHasPilotEvidence: false,
  scenarios: [],
  packLimitations: [],
  safetyStatement: ""
};

export async function getPilotMetrics() {
  const result = await tenantServerGetJsonNullable<PilotMetrics>("/api/v1/pilot/metrics");
  return { data: result.data ?? EMPTY_METRICS, error: result.error };
}

export async function getPilotExceptionBreakdown() {
  const result = await tenantServerGetJsonNullable<PilotExceptionBreakdown>(
    "/api/v1/pilot/metrics/exceptions"
  );
  return { data: result.data ?? EMPTY_EXCEPTIONS, error: result.error };
}

export async function getPilotEvidenceReport() {
  const result = await tenantServerGetJsonNullable<PilotEvidenceReport>("/api/v1/pilot/evidence-report");
  return { data: result.data ?? EMPTY_EVIDENCE, error: result.error };
}

export async function getPilotDemoScenarios() {
  const result = await tenantServerGetJsonNullable<PilotDemoScenarioPack>("/api/v1/pilot/demo-scenarios");
  return { data: result.data ?? EMPTY_DEMO_PACK, error: result.error };
}
