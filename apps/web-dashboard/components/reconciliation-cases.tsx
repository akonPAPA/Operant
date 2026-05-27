import { getStage8ProductTimeline, getStage8ReconciliationCases, getStage8ReconciliationSummary } from "@/lib/stage8-analytics-api";

export async function ReconciliationCases() {
  const [summary, cases] = await Promise.all([
    getStage8ReconciliationSummary(),
    getStage8ReconciliationCases()
  ]);
  const firstCase = cases?.cases?.[0];
  const timeline = firstCase ? await getStage8ProductTimeline(firstCase.productId) : null;

  return (
    <>
      <section className="panel">
        <h2>Inventory Analytics</h2>
        <div className="kpi-grid">
          <Metric label="Inventory mismatch count" value={summary?.inventoryMismatchCount ?? "n/a"} />
          <Metric label="High severity discrepancy count" value={summary?.highSeverityDiscrepancyCount ?? "n/a"} />
          <Metric label="Stale inventory count" value={summary?.staleInventoryCount ?? "n/a"} />
          <Metric label="Low stock count" value={summary?.lowStockCount ?? "n/a"} />
        </div>
        <p className="risk-note">Reconciliation is detection and reporting only. Refresh does not mutate inventory, orders, quotes, connector state, or external systems.</p>
      </section>

      <section className="panel table-panel">
        <h2>Reconciliation cases</h2>
        <table className="data-table">
          <thead>
            <tr><th>Product</th><th>Location</th><th>Expected</th><th>Actual</th><th>Mismatch</th><th>Severity</th><th>Likely causes</th></tr>
          </thead>
          <tbody>
            {(cases?.cases ?? []).length === 0 ? (
              <tr><td colSpan={7}>No reconciliation cases are available for the current tenant.</td></tr>
            ) : cases?.cases.map((item) => (
              <tr key={item.id}>
                <td>{item.productId}</td>
                <td>{item.locationId}</td>
                <td>{item.expectedStock}</td>
                <td>{item.actualStock}</td>
                <td>{item.mismatchQuantity}</td>
                <td><span className="severity-badge">{item.severity}</span></td>
                <td>{formatLikelyCauses(item.likelyCauses)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel table-panel">
        <h2>Product expected vs actual stock</h2>
        <p className="risk-note">Product-level timeline uses mirrored inventory movements and latest reconciliation cases.</p>
        <table className="data-table">
          <thead><tr><th>Movement</th><th>Quantity</th><th>Occurred</th><th>Source</th></tr></thead>
          <tbody>
            {(timeline?.movements ?? []).length === 0 ? (
              <tr><td colSpan={4}>Select a reconciliation case with movement history to show expected vs actual stock.</td></tr>
            ) : timeline?.movements.map((movement) => (
              <tr key={movement.id}>
                <td>{movement.movementType}</td>
                <td>{movement.quantity}</td>
                <td>{movement.occurredAt}</td>
                <td>{movement.sourceType}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}

function Metric({ label, value }: Readonly<{ label: string; value: number | string }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function formatLikelyCauses(value: string) {
  return value.replaceAll("[", "").replaceAll("]", "").replaceAll("\"", "").replaceAll(",", " | ");
}
