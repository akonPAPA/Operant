import { usesBffTransport } from "./api-transport.ts";

export type UploadCapability = "AVAILABLE" | "NOT_AVAILABLE_IN_PRODUCTION_BFF";

export function uploadCapability(): UploadCapability {
  return usesBffTransport() ? "NOT_AVAILABLE_IN_PRODUCTION_BFF" : "AVAILABLE";
}

export function isUploadAvailable(capability: UploadCapability = uploadCapability()): boolean {
  return capability === "AVAILABLE";
}

export function uploadUnavailableMessage(): string {
  return "Document upload is not available in this deployment.";
}