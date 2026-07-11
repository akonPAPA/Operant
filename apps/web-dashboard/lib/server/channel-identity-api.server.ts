import "server-only";

import type { ChannelIdentity } from "../channel-identity-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type { ChannelIdentity } from "../channel-identity-api.ts";

export function listChannelIdentities() {
  return tenantServerGetJson<ChannelIdentity[]>("/api/v1/channel-identities");
}
