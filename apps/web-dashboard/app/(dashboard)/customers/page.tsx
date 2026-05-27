import { DashboardShell } from "@/components/dashboard-shell";
import { listCustomers } from "@/lib/stage2-data-api";

export default async function Page() {
  const result = await listCustomers();
  const rows = result.data.slice(0, 25);

  return (
    <DashboardShell title="Customers">
      <section className="panel">
        <h2>Customer account foundation</h2>
        <p>{result.message ?? "Customer records are tenant-owned mirror data imported through staging and validation."}</p>
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
            {rows.length === 0 ? (
              <tr>
                <td>Pending data</td>
                <td>Run Stage 2 seed</td>
                <td>Demo fixture</td>
                <td>Not loaded</td>
              </tr>
            ) : rows.map((customer) => (
              <tr key={customer.id}>
                <td>{customer.accountCode}</td>
                <td>{customer.legalName}</td>
                <td>{customer.defaultCurrency}</td>
                <td>{customer.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
