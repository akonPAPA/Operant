import { DashboardShell } from "@/components/dashboard-shell";
import { OrderJourneyList } from "@/components/order-journey-list";

export default function OrderJourneyPage() {
  return (
    <DashboardShell title="Order Journey">
      <div className="page-grid">
        <section className="panel">
          <h2>Commercial transaction visibility</h2>
          <p>
            Operational lifecycle of quotes, orders, and fulfillment signals. Statuses are derived from
            trusted internal workflow and verified/mirrored signals — never invented by the frontend.
          </p>
        </section>
        <OrderJourneyList />
      </div>
    </DashboardShell>
  );
}
