import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

export default function Page() {
  return (
    <DashboardShell title="Processing Jobs">
      <UnavailableState
        title="Processing Jobs unavailable"
        description="This placeholder job table is not offered. Intake job status is available from Inbox when authorized."
        reason="PLACEHOLDER_UNSUPPORTED"
      />
    </DashboardShell>
  );
}
