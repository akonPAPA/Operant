import { DashboardShell } from "@/components/dashboard-shell";
import { IntegrationControl } from "@/components/integration-control";

export default function Page() {
  return (
    <DashboardShell title="Settings / Integrations">
      <IntegrationControl />
    </DashboardShell>
  );
}
