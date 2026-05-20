import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Inventory">
      <section className="panel">
        <h2>Latest inventory snapshots</h2>
        <p>Inventory is represented as snapshots from controlled sources; Stage 2 does not write external warehouse systems.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Product</th>
              <th>Location</th>
              <th>On Hand</th>
              <th>Available</th>
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