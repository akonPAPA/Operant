import { DashboardShell } from "@/components/dashboard-shell";
import { EmptyState } from "@/components/empty-state";

export default function Page() {
  return (
    <DashboardShell title="Settings">
      <EmptyState
        title="Settings is ready for Stage 2+ workflows"
        description="Run scripts/seed-demo-data/seed-core-v1.ps1 from the repo root to load Stage 2 demo data through core-api. No dashboard code writes the database directly."
      />
    </DashboardShell>
  );
}
