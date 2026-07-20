import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

export default function Page() {
  return (
    <DashboardShell title="Exception Cockpit">
      <UnavailableState
        title="Exception Cockpit unavailable"
        description="Use Validation Review for the live review-queue contract. This placeholder surface is not offered."
        reason="PLACEHOLDER_UNSUPPORTED"
      />
    </DashboardShell>
  );
}
