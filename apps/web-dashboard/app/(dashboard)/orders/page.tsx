import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

/** Static placeholder — not offered until a real tenant-scoped read contract is wired. */
export default function Page() {
  return (
    <DashboardShell title="Draft Order Workspace">
      <UnavailableState
        title="Draft Orders unavailable"
        description="This destination is not offered until a real draft-order list contract replaces placeholder content."
        reason="PLACEHOLDER_UNSUPPORTED — no fabricated order rows are shown."
      />
    </DashboardShell>
  );
}
