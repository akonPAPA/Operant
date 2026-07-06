import { DashboardShell } from "@/components/dashboard-shell";
import { RuntimeControlTelemetryPanel } from "@/components/runtime-control-telemetry-panel";
import { getRuntimeControlDemoFlowTelemetry } from "@/lib/runtime-control-telemetry-api";

export default async function Page() {
  const { data, error } = await getRuntimeControlDemoFlowTelemetry();

  return (
    <DashboardShell title="Runtime Control Telemetry">
      <RuntimeControlTelemetryPanel data={data} error={error} />
    </DashboardShell>
  );
}
