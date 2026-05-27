import { DashboardShell } from "@/components/dashboard-shell";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Extraction Detail">
      <div className="page-grid">
        <section className="panel"><h2>Source</h2><p>Run {id}: source metadata and extracted text preview live here.</p></section>
        <section className="panel"><h2>Intent</h2><p>Detected intent, document type, overall confidence, and prompt injection warnings.</p></section>
        <section className="panel"><h2>Review</h2><p>Mark fields as needs review, reject, or accept for Stage 5 validation. This is not business approval.</p></section>
      </div>
      <section className="panel action-panel">
        <h2>Deterministic Validation</h2>
        <p>Run deterministic validation when extraction output is ready for customer, product, inventory, pricing, discount, margin, compatibility, and substitution checks.</p>
        <button className="button" type="button">Run Deterministic Validation</button>
        <p className="risk-note">This action creates validation workflow records only. It does not create quotes/orders or update ERP.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Field</th><th>Raw Value</th><th>Confidence</th><th>Evidence</th><th>Status</th></tr></thead>
          <tbody><tr><td>SKU</td><td>Pending</td><td>0.00</td><td>Text range</td><td>Advisory</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
