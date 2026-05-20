import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Extraction Runs">
      <section className="panel">
        <h2>Advisory understanding pipeline</h2>
        <p>Stage 4 extraction results classify intent, fields, line items, evidence, and warnings. They do not create quotes, orders, inventory changes, or ERP writes.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Status</th><th>Source</th><th>Detected Intent</th><th>Confidence</th></tr></thead>
          <tbody><tr><td>Waiting</td><td>Inbound document / message</td><td>RFQ / PO / Unknown</td><td>Advisory score</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}