import { demoTenantId } from "./frontend-authority.mjs";

const DEFAULT_BASE_URL = "http://localhost:8080";

export const stage9IntegrationConfig = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: demoTenantId()
};

export type Stage9Integration = {
  id: string;
  providerType: string;
  displayName: string;
  status: string;
  mode: string;
  connectionKind: string;
  endpointRef?: string;
};

// Wave 01H Category D: the connector change-request response is operator-safe. The business-facing
// rollup `status` conveys execution readiness/outcome; the raw internal execution machinery
// (executionStatus, connectorFailureType, connectorRetryable) is not part of the contract.
export type Stage9ChangeRequest = {
  id: string;
  status: string;
  targetSystem: string;
  targetEntity: string;
  requestedAction: string;
  sourceType: string;
  approvalStatus: string;
  externalReference?: string;
  failureReason?: string;
};

export type Stage9ConnectorSyncRun = {
  id: string;
  integrationConnectionId: string;
  providerType: string;
  syncType: string;
  direction: string;
  status: string;
  recordsRead: number;
  recordsWritten: number;
  recordsFailed: number;
  errorCode?: string;
  errorMessage?: string;
};

export type Stage9ConnectorPolicy = {
  executionMode: string;
  capabilities: string[];
  credentialStatus: string;
  maskedCredentialRef: string;
  productionWritesEnabled: boolean;
  networkCallsAllowed: boolean;
  warning: string;
};

export type Stage9ConnectorAuditEvent = {
  id: string;
  action: string;
  entityType: string;
  entityId: string;
  metadata: string;
  occurredAt: string;
};

export async function getStage9Integrations(): Promise<Stage9Integration[]> {
  const response = await requestStage9<{ integrations: Stage9Integration[] }>("/api/stage9/integrations");
  return response?.integrations ?? [];
}

export async function getStage9ChangeRequests(): Promise<Stage9ChangeRequest[]> {
  const response = await requestStage9<{ changeRequests: Stage9ChangeRequest[] }>("/api/stage9/change-requests");
  return response?.changeRequests ?? [];
}

export async function getStage9ConnectorSyncRuns(): Promise<Stage9ConnectorSyncRun[]> {
  const response = await requestStage9<{ syncRuns: Stage9ConnectorSyncRun[] }>("/api/stage9/connector-sync-runs");
  return response?.syncRuns ?? [];
}

export async function getStage9ConnectorPolicy(): Promise<Stage9ConnectorPolicy | null> {
  return requestStage9<Stage9ConnectorPolicy>("/api/stage9/connectors/policies");
}

export async function getStage9ConnectorAudit(): Promise<Stage9ConnectorAuditEvent[]> {
  const response = await requestStage9<{ events: Stage9ConnectorAuditEvent[] }>("/api/stage9/connector-audit");
  return response?.events ?? [];
}

async function requestStage9<T>(path: string): Promise<T | null> {
  if (!stage9IntegrationConfig.tenantId) return null;
  try {
    const response = await fetch(`${stage9IntegrationConfig.baseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": stage9IntegrationConfig.tenantId }
    });
    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    return null;
  }
}
