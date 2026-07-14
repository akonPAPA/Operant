import "server-only";

import type { BotRuntimeConfig, BotRuntimeConfigListItem } from "../bot-runtime-config-api.ts";
import { tenantServerGetJson, tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type { BotRuntimeConfig, BotRuntimeConfigListItem } from "../bot-runtime-config-api.ts";

export function getBotRuntimeConfigurations() {
  return tenantServerGetJson<BotRuntimeConfigListItem[]>("/api/v1/bot-runtime/configurations");
}

export function getBotRuntimeConfiguration(connectionId: string) {
  return tenantServerGetJsonNullable<BotRuntimeConfig>(
    `/api/v1/bot-runtime/configurations/${connectionId}`
  );
}
