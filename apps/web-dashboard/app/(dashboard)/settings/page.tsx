import { DashboardShell } from "@/components/dashboard-shell";
import { EmptyState } from "@/components/empty-state";

export default function Page() {
  return (
    <DashboardShell title="Settings">
      <EmptyState
        title="Settings is ready for Stage 2+ workflows"
        description="This foundation screen is intentionally empty until the relevant backend domain model and safe command/query APIs exist."
      />
    </DashboardShell>
  );
}