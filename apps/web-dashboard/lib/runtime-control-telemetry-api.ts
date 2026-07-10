import { dashboardFetchHeaders, dashboardFetchUrl } from "./bff/dashboard-fetch";
import { dashboardCoreApiBaseUrl } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// Read-only tenant operator client for Runtime Control Telemetry (RFQ/AI/demo path). The browser sends
// no request body, authority fields, source filters, status, or runtime authority. The backend resolves
// tenant scope from the header and returns an allowlisted public DTO. This surface never mutates state.

export type RuntimeControlMeasurementKind =
  | "MEASURED"
  | "STATIC_CONTRACT"
  | "NOT_MEASURED"
  | "NOT_APPLICABLE";

export type RuntimeControlTelemetryValue = {
  kind: RuntimeControlMeasurementKind;
  value: string | null;
  explanation: string;
};

export type RuntimeControlSafetyPosture = {
  runtimeControlView: string;
  connectorInvocation: string;
  externalExecution: string;
  guardEvaluation: string;
  telemetryCompleteness: string;
  statement: string;
};

export type RuntimeControlWorkloadPosture = {
  pathStep: string;
  label: string;
  workloadType: RuntimeControlTelemetryValue;
  executionPosture: RuntimeControlTelemetryValue;
  costPath: RuntimeControlTelemetryValue;
  guardPosture: RuntimeControlTelemetryValue;
};

export type RuntimeControlAdmissionPosture = {
  runtimeControlEnabled: RuntimeControlTelemetryValue;
  aiWorkloadEnabled: RuntimeControlTelemetryValue;
  maxCostUnitsPerRequest: RuntimeControlTelemetryValue;
  maxSyncCostUnits: RuntimeControlTelemetryValue;
  backpressureQueueDepth: RuntimeControlTelemetryValue;
  admittedCount: RuntimeControlTelemetryValue;
  deniedCount: RuntimeControlTelemetryValue;
};

export type RuntimeControlProvenGuarantee = {
  code: string;
  label: string;
  statement: string;
};

export type RuntimeControlNotMeasured = {
  code: string;
  label: string;
  explanation: string;
};

export type RuntimeControlDemoFlowTelemetry = {
  generatedAt: string;
  scopeLabel: string;
  safety: RuntimeControlSafetyPosture;
  workloadPostures: RuntimeControlWorkloadPosture[];
  admission: RuntimeControlAdmissionPosture;
  provenGuarantees: RuntimeControlProvenGuarantee[];
  notMeasured: RuntimeControlNotMeasured[];
};

export type RuntimeControlTelemetryResult = {
  data: RuntimeControlDemoFlowTelemetry | null;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const runtimeControlTelemetryClient = {
  baseUrl:
    dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function statusMessage(status: number): string {
  switch (status) {
    case 403:
      return "You do not have access to runtime-control telemetry.";
    case 404:
      return "Runtime-control telemetry is not available.";
    case 429:
    case 503:
      return "Runtime-control telemetry is temporarily unavailable. Please try again shortly.";
    default:
      return "Runtime-control telemetry could not be loaded.";
  }
}

// Bounded, safe error strings. A malformed body or a contract-drifted body is NEVER echoed back to the
// UI — only these fixed messages are returned, so no raw backend body, JSON dump, or stack trace leaks.
const INVALID_RESPONSE = "Runtime-control telemetry response is invalid.";
const INVALID_CONTRACT = "Runtime-control telemetry contract is invalid.";

const MEASUREMENT_KINDS = new Set<RuntimeControlMeasurementKind>([
  "MEASURED",
  "STATIC_CONTRACT",
  "NOT_MEASURED",
  "NOT_APPLICABLE"
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isTelemetryValue(value: unknown): value is RuntimeControlTelemetryValue {
  return (
    isRecord(value) &&
    typeof value.kind === "string" &&
    MEASUREMENT_KINDS.has(value.kind as RuntimeControlMeasurementKind) &&
    (typeof value.value === "string" || value.value === null) &&
    typeof value.explanation === "string"
  );
}

function isSafetyPosture(value: unknown): value is RuntimeControlSafetyPosture {
  return (
    isRecord(value) &&
    typeof value.runtimeControlView === "string" &&
    typeof value.connectorInvocation === "string" &&
    typeof value.externalExecution === "string" &&
    typeof value.guardEvaluation === "string" &&
    typeof value.telemetryCompleteness === "string" &&
    typeof value.statement === "string"
  );
}

function isWorkloadPosture(value: unknown): value is RuntimeControlWorkloadPosture {
  return (
    isRecord(value) &&
    typeof value.pathStep === "string" &&
    typeof value.label === "string" &&
    isTelemetryValue(value.workloadType) &&
    isTelemetryValue(value.executionPosture) &&
    isTelemetryValue(value.costPath) &&
    isTelemetryValue(value.guardPosture)
  );
}

function isAdmissionPosture(value: unknown): value is RuntimeControlAdmissionPosture {
  return (
    isRecord(value) &&
    isTelemetryValue(value.runtimeControlEnabled) &&
    isTelemetryValue(value.aiWorkloadEnabled) &&
    isTelemetryValue(value.maxCostUnitsPerRequest) &&
    isTelemetryValue(value.maxSyncCostUnits) &&
    isTelemetryValue(value.backpressureQueueDepth) &&
    isTelemetryValue(value.admittedCount) &&
    isTelemetryValue(value.deniedCount)
  );
}

function isCodeLabelEntry(value: unknown): boolean {
  return isRecord(value) && typeof value.code === "string" && typeof value.label === "string";
}

function isRuntimeControlDemoFlowTelemetry(
  value: unknown
): value is RuntimeControlDemoFlowTelemetry {
  return (
    isRecord(value) &&
    typeof value.generatedAt === "string" &&
    typeof value.scopeLabel === "string" &&
    isSafetyPosture(value.safety) &&
    Array.isArray(value.workloadPostures) &&
    value.workloadPostures.every(isWorkloadPosture) &&
    isAdmissionPosture(value.admission) &&
    Array.isArray(value.provenGuarantees) &&
    value.provenGuarantees.every(isCodeLabelEntry) &&
    Array.isArray(value.notMeasured) &&
    value.notMeasured.every(isCodeLabelEntry)
  );
}

export async function getRuntimeControlDemoFlowTelemetry(): Promise<RuntimeControlTelemetryResult> {
  if (!runtimeControlTelemetryClient.tenantId) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }

  const headers: Record<string, string> = {
    "X-OrderPilot-Permissions": ANALYTICS_READ,
    "X-Tenant-Id": runtimeControlTelemetryClient.tenantId
  };

  // Network/transport failures only.
  let response: Response;
  let text: string;
  try {
    response = await fetch(
      `${runtimeControlTelemetryClient.baseUrl}/api/v1/runtime-control/demo-flow`,
      {
        cache: "no-store",
        method: "GET",
        headers
      }
    );
    if (!response.ok) {
      // Raw backend bodies are deliberately ignored; only a mapped status message is surfaced.
      return { data: null, error: statusMessage(response.status) };
    }
    text = await response.text();
  } catch {
    return { data: null, error: "Core API is not reachable." };
  }

  if (!text) {
    return { data: null };
  }

  // Malformed JSON is distinct from a network failure and from contract drift.
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { data: null, error: INVALID_RESPONSE };
  }

  // Shape / contract validation. A drifted or partial body never renders — it maps to a bounded message
  // and the raw parsed content is discarded (never echoed).
  if (!isRuntimeControlDemoFlowTelemetry(parsed)) {
    return { data: null, error: INVALID_CONTRACT };
  }

  return { data: parsed };
}
