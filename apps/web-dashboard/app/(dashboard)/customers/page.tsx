import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Customers">
      <section className="panel">
        <h2>Customer account foundation</h2>
        <p>Customer records are tenant-owned mirror data and future imports must pass through staging and validation first.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>Account Code</th>
              <th>Legal Name</th>
              <th>Segment</th>
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