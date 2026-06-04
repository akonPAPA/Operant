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

export type ChannelBotApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const channelBotConfig = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

async function getJson<T>(path: string, fallback: T): Promise<ChannelBotApiResult<T>> {
  if (!channelBotConfig.tenantId) {
    return { data: fallback, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped messenger bridge data." };
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

export function getChannelBotEvents() {
  return getJson<ChannelBotEvent[]>("/api/v1/channels/bot-events", []);
}
