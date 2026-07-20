import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

export default function Page() {
  return (
    <DashboardShell title="Webhook Events">
      <UnavailableState
        title="Webhook Events unavailable"
        description="This placeholder list is not offered. Use Messenger Bridge for live channel bot event reads when authorized."
        reason="PLACEHOLDER_UNSUPPORTED"
      />
    </DashboardShell>
  );
}
