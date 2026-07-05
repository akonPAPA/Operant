import { CommerceIntelligenceDemoFlowView } from "@/components/commerce-intelligence-demo-flow";
import { DashboardShell } from "@/components/dashboard-shell";
import { getCommerceIntelligenceDemoFlow } from "@/lib/commerce-intelligence-api";

export default async function Page() {
  const { data, error } = await getCommerceIntelligenceDemoFlow();

  return (
    <DashboardShell title="Commerce Intelligence">
      <CommerceIntelligenceDemoFlowView data={data} error={error} />
    </DashboardShell>
  );
}
