import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

export default function Page() {
  return (
    <DashboardShell title="Sync Events">
      <UnavailableState
        title="Sync Events unavailable"
        description="This placeholder sync history is not offered until a coherent single-permission sync surface exists."
        reason="PLACEHOLDER_UNSUPPORTED"
      />
    </DashboardShell>
  );
}
