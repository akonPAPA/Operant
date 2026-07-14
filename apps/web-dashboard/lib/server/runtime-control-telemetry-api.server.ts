import "server-only";

import type {
  RuntimeControlDemoFlowTelemetry,
  RuntimeControlTelemetryResult
} from "../runtime-control-telemetry-api.ts";
import { tenantServerGetJsonNullable } from "./tenant-get-json.server.ts";

export type {
  RuntimeControlDemoFlowTelemetry,
  RuntimeControlTelemetryResult
} from "../runtime-control-telemetry-api.ts";

export async function getRuntimeControlDemoFlowTelemetry(): Promise<RuntimeControlTelemetryResult> {
  return tenantServerGetJsonNullable<RuntimeControlDemoFlowTelemetry>(
    "/api/v1/runtime-control/demo-flow"
  );
}
