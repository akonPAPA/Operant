import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Documents">
      <section className="panel">
        <h2>Document intake</h2>
        <p>Use the extraction action placeholder to run Stage 4 understanding. Extraction results remain advisory until Stage 5 deterministic validation.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Filename</th><th>Type</th><th>Source</th><th>Status</th><th>Action</th></tr></thead>
          <tbody><tr><td>No documents received</td><td>Unknown</td><td>Manual/API/Email</td><td>Queued after intake</td><td>Run Extraction</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}