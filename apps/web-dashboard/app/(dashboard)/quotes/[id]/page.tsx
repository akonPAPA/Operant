import { DashboardShell } from "@/components/dashboard-shell";
import { Timeline } from "@/components/timeline";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Draft Quote Detail">
      <section className="panel">
        <h2>Quote {id}</h2>
        <p>Review quote lines, validation status, margin/discount warnings, approval status, notes, and audit timeline.</p>
        <p className="risk-note">Internal approve/reject/cancel actions do not send a quote externally.</p>
      </section>
      <section className="panel table-panel"><table className="data-table"><thead><tr><th>Line</th><th>Product</th><th>Qty</th><th>UOM</th><th>Price</th><th>Validation</th></tr></thead><tbody><tr><td>1</td><td>Pending</td><td>Pending</td><td>EA</td><td>Pending</td><td>NEEDS_REVIEW</td></tr></tbody></table></section>
      <section className="panel"><h2>Timeline</h2><Timeline items={[{ action: "QUOTE_DRAFT_CREATED", message: "Internal draft quote created.", createdAt: "Pending API data" }]} /></section>
    </DashboardShell>
  );
}
