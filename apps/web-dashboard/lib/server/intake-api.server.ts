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

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isIntakeDocumentArray(value: unknown): value is IntakeDocument[] {
  return Array.isArray(value) && value.every((entry) => isObjectRecord(entry) && typeof entry.id === "string");
}

function isInboundEventArray(value: unknown): value is InboundEvent[] {
  return Array.isArray(value) && value.every((entry) => isObjectRecord(entry) && typeof entry.id === "string");
}

export function getIntakeDocuments() {
  return tenantServerGetJson<IntakeDocument[]>("/api/v1/intake/documents", isIntakeDocumentArray);
}

export function getIntakeDocument(id: string) {
  return tenantServerGetJson<IntakeDocument>(`/api/v1/intake/documents/${id}`, (value): value is IntakeDocument => {
    return isObjectRecord(value) && typeof value.id === "string";
  });
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
  return tenantServerGetJson<InboundEvent[]>("/api/v1/intake/events", isInboundEventArray);
}
