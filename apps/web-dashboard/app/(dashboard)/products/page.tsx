import { DashboardShell } from "@/components/dashboard-shell";
import { listProducts } from "@/lib/stage2-data-api";

export default async function Page() {
  const result = await listProducts();
  const rows = result.data.slice(0, 25);

  return (
    <DashboardShell title="Products">
      <section className="panel">
        <h2>Product mirror foundation</h2>
        <p>{result.message ?? "Stage 2 product data is loaded through core-api. The dashboard has no direct database path."}</p>
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
            {rows.length === 0 ? (
              <tr>
                <td>Pending data</td>
                <td>Run Stage 2 seed</td>
                <td>Demo fixture</td>
                <td>Not loaded</td>
              </tr>
            ) : rows.map((product) => (
              <tr key={product.id}>
                <td>{product.sku}</td>
                <td>{product.name}</td>
                <td>{product.category ?? "Uncategorized"}</td>
                <td>{product.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
