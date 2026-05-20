import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Pricing">
      <section className="panel">
        <h2>Price rule foundation</h2>
        <p>Price rules are backend-owned data and not directly edited through the browser or AI worker.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Product</th>
              <th>Customer/Segment</th>
              <th>Unit Price</th>
              <th>Currency</th>
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