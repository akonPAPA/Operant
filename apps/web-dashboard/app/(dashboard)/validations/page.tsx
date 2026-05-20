import { DashboardShell } from "@/components/dashboard-shell";

const runs = [
  { id: "latest", status: "NEEDS_REVIEW", overall: "VALID_WITH_WARNINGS", confidence: "0.80", extraction: "ExtractionResult", created: "Pending API data" }
];

export default function Page() {
  return (
    <DashboardShell title="Validation Runs">
      <section className="panel">
        <h2>Deterministic Validation</h2>
        <p>Validation runs compare advisory extraction output with tenant customer, product, inventory, pricing, discount, margin, compatibility, and substitution rules.</p>
        <p className="risk-note">Validation output is review workflow state. It does not create a quote/order or update ERP, 1C, accounting, warehouse, product, customer, inventory, or pricing master data.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Run</th><th>Status</th><th>Overall</th><th>Confidence</th><th>Source</th><th>Created</th></tr></thead>
          <tbody>
            {runs.map((run) => (
              <tr key={run.id}><td><a href={`/validations/${run.id}`}>{run.id}</a></td><td>{run.status}</td><td>{run.overall}</td><td>{run.confidence}</td><td>{run.extraction}</td><td>{run.created}</td></tr>
            ))}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
