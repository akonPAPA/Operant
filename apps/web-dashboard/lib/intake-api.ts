import { dashboardCoreApiBaseUrl, dashboardRequestHeaders, isDashboardApiAuthorityAvailable } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

export type IntakeDocument = {
  id: string;
  sourceChannel: string;
  documentType: string;
  status: string;
  originalFilename?: string;
  contentType?: string;
  fileSizeBytes?: number;
  sha256Fingerprint?: string;
  receivedAt: string;
};

export type IntakeMessage = {
  id: string;
  channel: string;
  externalMessageId?: string;
  conversationId?: string;
  senderHandle?: string;
  messageType: string;
  textContent?: string;
  status: string;
  receivedAt: string;
};

export type IntakeJob = {
  id: string;
  jobType: string;
  targetType: string;
  targetId: string;
  status: string;
  queuedAt: string;
};

export type InboundEvent = {
  id: string;
  source: string;
  externalEventId?: string;
  eventType: string;
  fingerprintSha256?: string;
  status: string;
  // OP-CAP-17E: backend exposes only a safe boolean; raw object storage keys never reach the client.
  rawPayloadStored?: boolean;
};

export type IntakeApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const intakeConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

async function getJson<T>(path: string): Promise<IntakeApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(intakeConfig.tenantId)) {
    return { data: [] as T, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await fetch(`${intakeConfig.baseUrl}${path}`, {
      cache: "no-store",
      headers: dashboardRequestHeaders(intakeConfig.tenantId)
    });

    if (!response.ok) {
      return { data: [] as T, error: `Core API returned ${response.status}.` };
    }

    return { data: (await response.json()) as T };
  } catch (error) {
    return {
      data: [] as T,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

export function getIntakeDocuments() {
  return getJson<IntakeDocument[]>("/api/v1/intake/documents");
}

export function getIntakeDocument(id: string) {
  return getJson<IntakeDocument>(`/api/v1/intake/documents/${id}`);
}

export function getIntakeMessages() {
  return getJson<IntakeMessage[]>("/api/v1/intake/messages");
}

export function getIntakeMessage(id: string) {
  return getJson<IntakeMessage>(`/api/v1/intake/messages/${id}`);
}

export function getIntakeJobs() {
  return getJson<IntakeJob[]>("/api/v1/intake/jobs");
}

export function getInboundEvents() {
  return getJson<InboundEvent[]>("/api/v1/intake/events");
}
