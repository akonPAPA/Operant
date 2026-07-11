import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit, isDashboardApiAuthorityAvailable } from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { demoTenantId } from "./frontend-authority.mjs";

export type ApiResult<T> = {
  data: T;
  error?: string;
};

export type BotConversation = {
  id: string;
  channel: string;
  externalChatId: string;
  status: string;
  requiresHumanReview: boolean;
  linkedReviewCaseId?: string;
  policyDecision?: string;
  suggestedNextAction?: string;
  createdAt: string;
  updatedAt: string;
};

export type BotMessage = {
  id: string;
  conversationId: string;
  channel: string;
  externalChatId: string;
  externalMessageId: string;
  rawText: string;
  detectedIntent: string;
  status: string;
  requiresHumanReview: boolean;
  createdAt: string;
};

export type BotHandoff = {
  id: string;
  conversationId: string;
  messageId: string;
  channelMessageId?: string;
  customerAccountId?: string;
  channel: string;
  reason: string;
  detectedIntent?: string;
  assignedQueue?: string;
  extractedHintsJson?: string;
  riskFlagsJson?: string;
  status: string;
  requiresHumanReview: boolean;
};

export type BotRuntimeSettings = {
  connectionId?: string;
  channelType: string;
  botExternalId?: string;
  telegramBotId?: string;
  enabled: boolean;
  allowedFlows: string[];
  defaultHandoffQueue: string;
  lastSeenAt?: string;
  updatedAt?: string;
  safeResponseTemplates: string[];
};

export type BotResponseDraft = {
  id: string;
  conversationId: string;
  channel: string;
  responseType: string;
  policyDecision: string;
  status: string;
  responseText: string;
  requiresOperatorReview: boolean;
  reviewedAt?: string;
  stubSentAt?: string;
  createdAt: string;
  updatedAt: string;
};

export type BotConversationDetail = {
  conversation: BotConversation;
  messages: BotMessage[];
  handoffs: BotHandoff[];
  responseDrafts: BotResponseDraft[];
};

export type BotReviewHandoff = {
  reviewCaseId: string;
  caseNumber: string;
  sourceType: string;
  sourceId: string;
  sourceConversationId: string;
  status: string;
  title: string;
  summary: string;
  reusedExisting: boolean;
  conversationId: string;
  sourceMessageId: string;
  rfqRequestId?: string;
  detectedIntent: string;
  policyDecision: string;
  latestMessage: string;
  handoffReason: string;
  nextActions: string[];
};

export type BotSimulateMessageResponse = {
  conversationId: string;
  messageId: string;
  intent: string;
  policyDecision: string;
  reasonCode: string;
  suggestedSafeResponse: string;
  requiresHumanReview: boolean;
  createdRfqDraftId?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const botRuntimeConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (botRuntimeConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = botRuntimeConfig.tenantId;
  }
  return requestHeaders;
}

async function requestJson<T>(path: string, init?: RequestInit, fallbackData?: T): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(botRuntimeConfig.tenantId)) {
    return { data: fallbackData as T, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await dashboardApiFetch(
      path,
      enrichDashboardRequestInit({
        cache: "no-store",
        ...init,
        headers: { ...headers(), ...(init?.headers ?? {}) }
      })
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : (fallbackData as T);
    if (!response.ok) {
      const message = typeof data === "object" && data && "message" in data ? String((data as { message?: string }).message) : text;
      return { data, error: message || `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: fallbackData as T, error: error instanceof Error ? error.message : "Core API is not reachable." };
  }
}

export function listBotConversations() {
  return requestJson<BotConversation[]>("/api/v1/bot-runtime/conversations", { method: "GET" }, []);
}

export function getBotRuntimeSettings() {
  return requestJson<BotRuntimeSettings>("/api/v1/bot-runtime/settings", { method: "GET" }, {
    channelType: "TELEGRAM",
    enabled: false,
    allowedFlows: [],
    defaultHandoffQueue: "BOT_REVIEW",
    safeResponseTemplates: []
  });
}

export function updateBotRuntimeSettings(settings: Pick<BotRuntimeSettings, "enabled" | "allowedFlows" | "defaultHandoffQueue">) {
  return requestJson<BotRuntimeSettings>("/api/v1/bot-runtime/settings", {
    method: "POST",
    body: JSON.stringify(settings)
  });
}

export function listBotHandoffs() {
  return requestJson<BotHandoff[]>("/api/v1/bot-runtime/handoffs", { method: "GET" }, []);
}

export function getBotConversation(conversationId: string) {
  return requestJson<BotConversationDetail>(`/api/v1/bot-runtime/conversations/${conversationId}`, { method: "GET" });
}

export async function listBotConversationDetails(): Promise<ApiResult<BotConversationDetail[]>> {
  const conversations = await listBotConversations();
  const details = await Promise.all((conversations.data ?? []).slice(0, 20).map((item) => getBotConversation(item.id)));
  return {
    data: details.map((item) => item.data).filter(Boolean),
    error: [conversations.error, ...details.map((item) => item.error)].filter(Boolean).join(" ")
  };
}

export function simulateBotMessage(text: string, externalChatId = "demo-chat") {
  return requestJson<BotSimulateMessageResponse>("/api/v1/bot-runtime/messages/simulate", {
    method: "POST",
    body: JSON.stringify({
      channel: "TELEGRAM",
      externalChatId,
      externalMessageId: `demo-${Date.now()}`,
      senderDisplayName: "Demo Buyer",
      text
    })
  });
}

export function createBotResponseDraft(conversationId: string, sourceMessageId?: string) {
  return requestJson<BotResponseDraft>(`/api/v1/bot-runtime/conversations/${conversationId}/responses/draft`, {
    method: "POST",
    body: JSON.stringify({ sourceMessageId, knownCustomerIdentity: false })
  });
}

export function markBotResponseReady(responseId: string) {
  return requestJson<BotResponseDraft>(`/api/v1/bot-runtime/responses/${responseId}/mark-ready`, {
    method: "POST",
    body: JSON.stringify({})
  });
}

export function stubSendBotResponse(responseId: string) {
  return requestJson<BotResponseDraft>(`/api/v1/bot-runtime/responses/${responseId}/stub-send`, { method: "POST" });
}

export function createBotReviewHandoff(conversationId: string) {
  return requestJson<BotReviewHandoff>(`/api/v1/bot-runtime/conversations/${conversationId}/review-handoff`, { method: "POST" });
}
