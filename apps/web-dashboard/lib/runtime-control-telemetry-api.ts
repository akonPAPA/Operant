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
    process.env.CORE_API_BASE_URL ??
    process.env.NEXT_PUBLIC_CORE_API_URL ??
    DEFAULT_BASE_URL,
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

export async function getRuntimeControlDemoFlowTelemetry(): Promise<RuntimeControlTelemetryResult> {
  if (!runtimeControlTelemetryClient.tenantId) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }

  const headers: Record<string, string> = {
    "X-OrderPilot-Permissions": ANALYTICS_READ,
    "X-Tenant-Id": runtimeControlTelemetryClient.tenantId
  };

  try {
    const response = await fetch(
      `${runtimeControlTelemetryClient.baseUrl}/api/v1/runtime-control/demo-flow`,
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
    return { data: text ? (JSON.parse(text) as RuntimeControlDemoFlowTelemetry) : null };
  } catch {
    return { data: null, error: "Core API is not reachable." };
  }
}
