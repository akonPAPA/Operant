import { usesBffTransport } from "./api-transport.ts";
import { bffRuntimeMode } from "./bff/bff-public-config.ts";

export type UploadCapability =
  | "AVAILABLE_LOCAL_DEMO"
  | "NOT_AVAILABLE_PRODUCTION_BFF"
  | "NOT_AVAILABLE_PRODUCTION_CONFIGURATION";

export function uploadCapability(): UploadCapability {
  if (usesBffTransport()) {
    return "NOT_AVAILABLE_PRODUCTION_BFF";
  }
  return bffRuntimeMode() === "unavailable"
    ? "NOT_AVAILABLE_PRODUCTION_CONFIGURATION"
    : "AVAILABLE_LOCAL_DEMO";
}

export function isUploadAvailable(capability: UploadCapability = uploadCapability()): boolean {
  return capability === "AVAILABLE_LOCAL_DEMO";
}

export function uploadUnavailableMessage(): string {
  return "Document upload is not available in this deployment.";
}
