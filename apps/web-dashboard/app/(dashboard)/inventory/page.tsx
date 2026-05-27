import { DashboardShell } from "@/components/dashboard-shell";
import { ReconciliationCases } from "@/components/reconciliation-cases";
import { listInventory } from "@/lib/stage2-data-api";

export default async function Page() {
  const result = await listInventory();
  const rows = result.data.slice(0, 25);

  return (
    <DashboardShell title="Inventory">
      <section className="panel">
        <h2>Latest inventory snapshots</h2>
        <p>{result.message ?? "Inventory snapshots are loaded through core-api. Stage 2 does not write external warehouse systems."}</p>
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
            {rows.length === 0 ? (
              <tr>
                <td>Pending data</td>
                <td>Run Stage 2 seed</td>
                <td>0</td>
                <td>0</td>
              </tr>
            ) : rows.map((snapshot) => (
              <tr key={snapshot.id}>
                <td>{snapshot.productId}</td>
                <td>{snapshot.locationId}</td>
                <td>{snapshot.quantityOnHand}</td>
                <td>{snapshot.quantityAvailable}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <ReconciliationCases />
    </DashboardShell>
  );
}
