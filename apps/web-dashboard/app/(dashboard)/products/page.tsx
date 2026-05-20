import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Products">
      <section className="panel">
        <h2>Product mirror foundation</h2>
        <p>Stage 2 exposes the product catalog API foundation and keeps catalog changes routed through core-api.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Name</th>
              <th>Category</th>
              <th>Status</th>
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