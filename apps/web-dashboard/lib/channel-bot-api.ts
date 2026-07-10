import { dashboardCoreApiBaseUrl } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-06A Messenger Chatbot Integration Layer (read-only client).
// Surfaces the bridge between a verified channel connection inbound event and the
// controlled bot runtime. No secrets, raw tokens, or raw provider payloads are requested.

export type ChannelBotEvent = {
  id: string;
  channelConnectionId: string;
  providerType: string;
  externalEventId?: string;
  sourceActorType: string;
  sourceActorExternalId?: string;
  normalizedText?: string;
  status: string;
  verificationStatus?: string;
  botConversationId?: string;
  botMessageId?: string;
  botRuntimeStatus?: string;
  receivedAt: string;
  processedAt?: string;
};

export type ChannelBotBridgeStatus = {
  externalExecution: string;
  recentWindowLimit: number;
  recentEventCount: number;
  bridgedToBotCount: number;
  pendingOrUnbridgedCount: number;
  supportedFlows: string[];
  forbiddenActions: string[];
};

export type ChannelBotApiResult<T> = {
  data: T;
  error?: string;
};

// Default operator read window. The backend independently clamps any limit to a safe maximum,
// so this is only a request hint and never the security boundary.
export const DEFAULT_BRIDGE_EVENT_LIMIT = 50;

const DEFAULT_BASE_URL = "http://localhost:8080";

export const channelBotConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

async function getJson<T>(path: string, fallback: T): Promise<ChannelBotApiResult<T>> {
  if (!channelBotConfig.tenantId) {
    return { data: fallback, error: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await fetch(`${channelBotConfig.baseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": channelBotConfig.tenantId }
    });
    if (!response.ok) {
      return { data: fallback, error: `Core API returned ${response.status}.` };
    }
    return { data: (await response.json()) as T };
  } catch (error) {
    return { data: fallback, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

export function getChannelBotEvents(limit: number = DEFAULT_BRIDGE_EVENT_LIMIT) {
  return getJson<ChannelBotEvent[]>(`/api/v1/channels/bot-events?limit=${encodeURIComponent(limit)}`, []);
}

const EMPTY_BRIDGE_STATUS: ChannelBotBridgeStatus = {
  externalExecution: "DISABLED",
  recentWindowLimit: DEFAULT_BRIDGE_EVENT_LIMIT,
  recentEventCount: 0,
  bridgedToBotCount: 0,
  pendingOrUnbridgedCount: 0,
  supportedFlows: [],
  forbiddenActions: []
};

export function getChannelBotBridgeStatus(limit: number = DEFAULT_BRIDGE_EVENT_LIMIT) {
  return getJson<ChannelBotBridgeStatus>(
    `/api/v1/channels/bot-bridge/status?limit=${encodeURIComponent(limit)}`,
    EMPTY_BRIDGE_STATUS
  );
}
