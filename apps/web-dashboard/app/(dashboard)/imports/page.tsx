import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Imports">
      <section className="panel">
        <h2>Import staging and validation</h2>
        <p>Imports stage rows, run deterministic validation, produce a report, and only then can be applied or rejected.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Type</th>
              <th>Status</th>
              <th>Rows</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Pending data</td>
              <td>Pending data</td>
              <td>Pending data</td>
              <td>Pending data</td>
            </tr>
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}