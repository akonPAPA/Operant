import "server-only";

import type { RfqHandoff, RfqHandoffStatus } from "../rfq-handoff-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type { RfqHandoff, RfqHandoffStatus } from "../rfq-handoff-api.ts";

export function listRfqHandoffs(status?: RfqHandoffStatus) {
  const query = status ? `?status=${encodeURIComponent(status)}` : "";
  return tenantServerGetJson<RfqHandoff[]>(`/api/v1/channels/rfq-handoffs${query}`);
}
