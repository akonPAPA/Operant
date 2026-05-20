import { DashboardShell } from "@/components/dashboard-shell";
import { Timeline } from "@/components/timeline";

export default function Page({ params }: Readonly<{ params: { id: string } }>) {
  return (
    <DashboardShell title="Draft Order Detail">
      <section className="panel">
        <h2>Order {params.id}</h2>
        <p>Review order lines, inventory warnings, substitute selection, approval state, notes, and timeline.</p>
        <p className="risk-note">Internal approve/reject/cancel actions do not write ERP, 1C, accounting, warehouse, or inventory.</p>
      </section>
      <section className="panel table-panel"><table className="data-table"><thead><tr><th>Line</th><th>Product</th><th>Substitute</th><th>Qty</th><th>Inventory</th><th>Validation</th></tr></thead><tbody><tr><td>1</td><td>Pending</td><td>Optional</td><td>Pending</td><td>Needs review</td><td>NEEDS_REVIEW</td></tr></tbody></table></section>
      <section className="panel"><h2>Timeline</h2><Timeline items={[{ action: "ORDER_DRAFT_CREATED", message: "Internal draft order created without inventory reservation.", createdAt: "Pending API data" }]} /></section>
    </DashboardShell>
  );
}
