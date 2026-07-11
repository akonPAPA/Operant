import "server-only";

import type {
  BotConversationDetail,
  BotHandoff,
  BotRuntimeSettings
} from "../bot-runtime-api.ts";
import { tenantServerGetJson, tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type { BotConversationDetail, BotHandoff, BotRuntimeSettings } from "../bot-runtime-api.ts";

const EMPTY_SETTINGS: BotRuntimeSettings = {
  channelType: "TELEGRAM",
  enabled: false,
  allowedFlows: [],
  defaultHandoffQueue: "BOT_REVIEW",
  safeResponseTemplates: []
};

export async function listBotConversationDetails() {
  const result = await tenantServerGetJson<BotConversationDetail[]>("/api/v1/bot-runtime/conversations");
  return { data: Array.isArray(result.data) ? result.data : [], error: result.error };
}

export async function getBotRuntimeSettings() {
  const result = await tenantServerGetJsonNullable<BotRuntimeSettings>("/api/v1/bot-runtime/settings");
  return { data: result.data ?? EMPTY_SETTINGS, error: result.error };
}

export async function listBotHandoffs() {
  const result = await tenantServerGetJson<BotHandoff[]>("/api/v1/bot-runtime/handoffs");
  return { data: Array.isArray(result.data) ? result.data : [], error: result.error };
}
