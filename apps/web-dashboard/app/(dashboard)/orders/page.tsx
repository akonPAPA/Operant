import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Draft Order Workspace">
      <section className="panel">
        <h2>Internal Draft Orders</h2>
        <p>Draft orders are internal review records created after deterministic validation.</p>
        <p className="risk-note">Internal draft only - does not write ERP/warehouse or reserve inventory.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Order</th><th>Status</th><th>Customer</th><th>Total</th><th>Inventory</th></tr></thead>
          <tbody><tr><td><a href="/orders/latest">Latest</a></td><td>NEEDS_REVIEW</td><td>Pending</td><td>Pending</td><td>Review required</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
