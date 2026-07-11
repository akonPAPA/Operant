import "server-only";

import type {
  InboundEvent,
  IntakeDocument,
  IntakeJob,
  IntakeMessage
} from "../intake-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type {
  InboundEvent,
  IntakeDocument,
  IntakeJob,
  IntakeMessage,
  IntakeApiResult
} from "../intake-api.ts";

export function getIntakeDocuments() {
  return tenantServerGetJson<IntakeDocument[]>("/api/v1/intake/documents");
}

export function getIntakeDocument(id: string) {
  return tenantServerGetJson<IntakeDocument>(`/api/v1/intake/documents/${id}`);
}

export function getIntakeMessages() {
  return tenantServerGetJson<IntakeMessage[]>("/api/v1/intake/messages");
}

export function getIntakeMessage(id: string) {
  return tenantServerGetJson<IntakeMessage>(`/api/v1/intake/messages/${id}`);
}

export function getIntakeJobs() {
  return tenantServerGetJson<IntakeJob[]>("/api/v1/intake/jobs");
}

export function getInboundEvents() {
  return tenantServerGetJson<InboundEvent[]>("/api/v1/intake/events");
}
