import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-06B / OP-CAP-06B.1 Controlled Bot Runtime Configuration client.
// Surfaces tenant-scoped, per-channel-connection bot runtime configuration and the safe, permissioned
// edit path. It requests and renders deterministic policy only — never bot tokens, secret references,
// or provider credentials. Configuration can only constrain bot behavior; the backend runtime and
// deterministic validation remain the final authority.

export type BotRuntimeConfigListItem = {
  channelConnectionId: string;
  providerType: string;
  displayName: string;
  connectionStatus: string;
  connectionVerificationMode: string;
  configured: boolean;
  enabled: boolean;
  updatedAt?: string;
};

export type BotRuntimeConfig = {
  id: string;
  channelConnectionId: string;
  providerType: string;
  connectionStatus: string;
  connectionVerificationMode: string;
  enabled: boolean;
  greetingEnabled: boolean;
  availabilityCheckEnabled: boolean;
  priceCheckMode: string;
  rfqCaptureMode: string;
  substituteSuggestionMode: string;
  orderStatusMode: string;
  unknownCustomerMode: string;
  humanHandoffEnabled: boolean;
  handoffQueueKey: string;
  inventoryFreshnessMaxMinutes: number;
  inventoryFreshnessPolicy: string;
  priceVisibilityPolicy: string;
  safeGreetingTemplate: string;
  safeFallbackTemplate: string;
  handoffTemplate: string;
  revision: number;
  externalExecution: string;
  createdAt?: string;
  updatedAt?: string;
};

// Mutable, safe subset only. No tenant id, no secrets, no tokens, no credentials.
export type BotRuntimeConfigUpdate = {
  enabled: boolean;
  greetingEnabled: boolean;
  availabilityCheckEnabled: boolean;
  priceCheckMode: string;
  rfqCaptureMode: string;
  substituteSuggestionMode: string;
  orderStatusMode: string;
  unknownCustomerMode: string;
  humanHandoffEnabled: boolean;
  handoffQueueKey: string;
  inventoryFreshnessMaxMinutes: number;
  inventoryFreshnessPolicy: string;
  priceVisibilityPolicy: string;
  safeGreetingTemplate: string;
  safeFallbackTemplate: string;
  handoffTemplate: string;
};

export type BotRuntimeConfigApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
// Writes are gated by the BOT_ACTION permission on /api/v1/bot-runtime/** (same interceptor as the
// rest of the bot runtime API). The browser declares the action permission like lib/demo-api.ts does
// for reads; the backend interceptor remains the real authority and re-checks tenant + permission.
const BOT_ACTION_PERMISSION = "BOT_ACTION";

export const botRuntimeConfigClient = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers(extra?: Record<string, string>): Record<string, string> {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (botRuntimeConfigClient.tenantId) {
    requestHeaders["X-Tenant-Id"] = botRuntimeConfigClient.tenantId;
  }
  return { ...requestHeaders, ...(extra ?? {}) };
}

async function requestJson<T>(path: string, init: RequestInit, fallback: T): Promise<BotRuntimeConfigApiResult<T>> {
  if (!botRuntimeConfigClient.tenantId) {
    return { data: fallback, error: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await fetch(
      `${botRuntimeConfigClient.baseUrl}${path}`,
      enrichDashboardRequestInit({
        cache: "no-store",
        ...init,
        headers: { ...headers(), ...((init.headers as Record<string, string>) ?? {}) }
      })
    );
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

export function getBotRuntimeConfigurations() {
  return requestJson<BotRuntimeConfigListItem[]>("/api/v1/bot-runtime/configurations", { method: "GET" }, []);
}

export function getBotRuntimeConfiguration(connectionId: string) {
  return requestJson<BotRuntimeConfig | null>(`/api/v1/bot-runtime/configurations/${connectionId}`, { method: "GET" }, null);
}

// Update safe configuration fields through the permissioned backend command path. The payload never
// includes a tenant id (resolved server-side) or any token/secret/credential.
export function updateBotRuntimeConfiguration(connectionId: string, update: BotRuntimeConfigUpdate) {
  return requestJson<BotRuntimeConfig | null>(
    `/api/v1/bot-runtime/configurations/${connectionId}`,
    {
      method: "PUT",
      headers: { "X-OrderPilot-Permissions": BOT_ACTION_PERMISSION },
      body: JSON.stringify(update)
    },
    null
  );
}

// Reset a connection's configuration to the backend safe defaults.
export function resetBotRuntimeConfiguration(connectionId: string) {
  return requestJson<BotRuntimeConfig | null>(
    `/api/v1/bot-runtime/configurations/${connectionId}/reset-defaults`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": BOT_ACTION_PERMISSION }
    },
    null
  );
}
