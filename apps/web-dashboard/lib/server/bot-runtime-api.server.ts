import "server-only";

import type {
  BotHandoff,
  BotRuntimeSettings
} from "../bot-runtime-api.ts";
import {
  listBotConversationDetailsWithReaders,
  type BotConversationDetailReaders
} from "../bot-conversation-details.ts";
import { tenantServerGetJson, tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type { BotConversationDetail, BotHandoff, BotRuntimeSettings } from "../bot-runtime-api.ts";

const EMPTY_SETTINGS: BotRuntimeSettings = {
  channelType: "TELEGRAM",
  enabled: false,
  allowedFlows: [],
  defaultHandoffQueue: "BOT_REVIEW",
  safeResponseTemplates: []
};

const DEFAULT_READERS: BotConversationDetailReaders = {
  getJson: tenantServerGetJson,
  getNullable: tenantServerGetJsonNullable
};

export async function listBotConversationDetails(readers: BotConversationDetailReaders = DEFAULT_READERS) {
  return listBotConversationDetailsWithReaders(readers);
}

export async function getBotRuntimeSettings() {
  const result = await tenantServerGetJsonNullable<BotRuntimeSettings>("/api/v1/bot-runtime/settings");
  return { data: result.data ?? EMPTY_SETTINGS, error: result.error };
}

export async function listBotHandoffs() {
  const result = await tenantServerGetJson<BotHandoff[]>("/api/v1/bot-runtime/handoffs");
  return { data: Array.isArray(result.data) ? result.data : [], error: result.error };
}