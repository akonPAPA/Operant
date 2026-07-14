import "server-only";

import {
  DEFAULT_BRIDGE_EVENT_LIMIT,
  type ChannelBotBridgeStatus,
  type ChannelBotEvent
} from "../channel-bot-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export { DEFAULT_BRIDGE_EVENT_LIMIT } from "../channel-bot-api.ts";
export type { ChannelBotBridgeStatus, ChannelBotEvent } from "../channel-bot-api.ts";

const EMPTY_BRIDGE_STATUS: ChannelBotBridgeStatus = {
  externalExecution: "DISABLED",
  recentWindowLimit: DEFAULT_BRIDGE_EVENT_LIMIT,
  recentEventCount: 0,
  bridgedToBotCount: 0,
  pendingOrUnbridgedCount: 0,
  supportedFlows: [],
  forbiddenActions: []
};

export function getChannelBotEvents(limit: number = DEFAULT_BRIDGE_EVENT_LIMIT) {
  return tenantServerGetJson<ChannelBotEvent[]>(
    `/api/v1/channels/bot-events?limit=${encodeURIComponent(limit)}`
  );
}

export function getChannelBotBridgeStatus(limit: number = DEFAULT_BRIDGE_EVENT_LIMIT) {
  return tenantServerGetJson<ChannelBotBridgeStatus>(
    `/api/v1/channels/bot-bridge/status?limit=${encodeURIComponent(limit)}`
  ).then((result) =>
    result.error ? { ...result, data: EMPTY_BRIDGE_STATUS } : result
  );
}
