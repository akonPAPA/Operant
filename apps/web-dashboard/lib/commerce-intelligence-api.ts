import { dashboardCoreApiBaseUrl, dashboardRequestHeaders, isDashboardApiAuthorityAvailable } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// Read-only tenant operator client. The browser sends no request body, authority fields, source
// filters, or date range. The backend resolves tenant scope and returns an allowlisted public DTO.

export type CommerceIntelligenceSummary = {
  rfqHandoffsTotal: number;
  pendingReviewCount: number;
  inReviewCount: number;
  convertedCount: number;
  dismissedCount: number;
  aiAdvisorySuggestionsCount: number;
  reviewRequiredDraftQuotesCount: number;
  safeTerminalDemoDecisionsCount: number;
  demoCompletedCount: number;
  demoDeclinedCount: number;
};

export type CommerceIntelligenceSafety = {
  externalWriteStatus: "DISABLED";
  connectorCallStatus: "NOT_INVOKED";
  outboxStatus: "NOT_REQUESTED";
  observedConnectorCommandRows: number | null;
  observedChangeRequestRows: number | null;
  observedOutboxRows: number | null;
  measurementScope: "DEMO_FLOW" | "TENANT_OBSERVED" | "NOT_MEASURED";
  safetyStatement: string;
  notProven: string[];
};

export type CommerceIntelligenceRuntimeControl = {
  guarded: boolean;
  demoRfqHandoffCreate: "RATE_BACKPRESSURE_GATED";
  rfqHandoffAiAdvisory: "AI_VALIDATION_EXPLANATION_GUARDED";
  draftQuoteCreate: "RATE_BACKPRESSURE_GATED";
  safeDemoDecision: "RATE_BACKPRESSURE_GATED";
  billingOrQuotaDimension: "NOT_APPLICABLE_FOR_DEMO_OPS";
  denialTelemetry: "NOT_MEASURED";
  note: string;
};

export type CommerceIntelligenceBottleneck = {
  code: string;
  label: string;
  count: number;
  severity: string;
  explanation: string;
};

export type CommerceIntelligenceRecentFlow = {
  handoffId: string;
  sourceChannel: string;
  requestPreview: string;
  detectedIntent: string;
  handoffStatus: string;
  aiSuggestionStatus: string;
  aiSchemaVersion: string | null;
  aiRiskLevel: string | null;
  draftQuoteStatus: string;
  validationStatus: string;
  safeTerminalState: string;
  blockingIssueCodes: string[];
  createdAt: string;
  updatedAt: string;
};

export type CommerceIntelligenceNotProven = {
  code: string;
  label: string;
  explanation: string;
};

export type CommerceIntelligenceDemoFlow = {
  generatedAt: string;
  windowLabel: string;
  summary: CommerceIntelligenceSummary;
  safety: CommerceIntelligenceSafety;
  runtimeControl: CommerceIntelligenceRuntimeControl;
  bottlenecks: CommerceIntelligenceBottleneck[];
  recentFlows: CommerceIntelligenceRecentFlow[];
  notProven: CommerceIntelligenceNotProven[];
};

export type CommerceIntelligenceResult = {
  data: CommerceIntelligenceDemoFlow | null;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const commerceIntelligenceClient = {
  baseUrl:
    dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function statusMessage(status: number): string {
  switch (status) {
    case 403:
      return "You do not have access to Commerce Intelligence.";
    case 404:
      return "Commerce Intelligence is not available.";
    case 429:
    case 503:
      return "Commerce Intelligence is temporarily unavailable. Please try again shortly.";
    default:
      return "Commerce Intelligence could not be loaded.";
  }
}

export async function getCommerceIntelligenceDemoFlow(): Promise<CommerceIntelligenceResult> {
  if (!isDashboardApiAuthorityAvailable(commerceIntelligenceClient.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }

  const headers = dashboardRequestHeaders(commerceIntelligenceClient.tenantId, ANALYTICS_READ);

  try {
    const response = await fetch(
      `${commerceIntelligenceClient.baseUrl}/api/v1/commerce-intelligence/demo-flow`,
      {
        cache: "no-store",
        method: "GET",
        headers
      }
    );
    if (!response.ok) {
      // Raw backend bodies are deliberately ignored.
      return { data: null, error: statusMessage(response.status) };
    }
    const text = await response.text();
    return { data: text ? (JSON.parse(text) as CommerceIntelligenceDemoFlow) : null };
  } catch {
    return { data: null, error: "Core API is not reachable." };
  }
}
