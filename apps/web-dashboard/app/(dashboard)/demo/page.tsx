import { DashboardShell } from "@/components/dashboard-shell";
import { DemoDashboard } from "@/components/demo-dashboard";

export default function InvestorDemoPage() {
  return (
    <DashboardShell title="Investor Demo">
      <DemoDashboard /> 
    </DashboardShell>
  );
}
