import { DashboardShell } from "@/components/dashboard-shell";
import { OrderJourneyDetail } from "@/components/order-journey-detail";

export default async function OrderJourneyDetailPage({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Order Journey">
      <div className="page-grid">
        <OrderJourneyDetail id={id} />
      </div>
    </DashboardShell>
  );
}
