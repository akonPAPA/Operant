import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

export default function Page() {
  return (
    <DashboardShell title="Settings / Channels">
      <UnavailableState
        title="Channels unavailable"
        description="Channel provider cards are not offered as a production surface until live channel configuration reads replace placeholder content."
        reason="PLACEHOLDER_UNSUPPORTED"
      />
    </DashboardShell>
  );
}
