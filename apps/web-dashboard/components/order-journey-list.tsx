import Link from "next/link";
import { getOrderJourneys } from "@/lib/order-journey-api";
import { BlockedBadge, EvidenceBadge } from "./order-journey-status-badge";

// OP-CAP-22 — Order Journey list. Server component consuming the bounded, tenant-scoped read API.
// Honest empty/error states; no fake data.
export async function OrderJourneyList() {
  const { data, error } = await getOrderJourneys();

  if (!data) {
    return (
      <section className="panel">
        <h2>Order journeys</h2>
        <p className="muted-copy">{error ?? "Order journey projection not connected yet."}</p>
      </section>
    );
  }

  if (data.items.length === 0) {
    return (
      <section className="panel">
        <h2>Order journeys</h2>
        <p className="muted-copy">No order journey signals yet.</p>
      </section>
    );
  }

  return (
    <section className="panel table-panel">
      <div className="status-row">
        <h2>Order journeys</h2>
        <span className="status-pill" data-blocked={data.blockedCount > 0 ? "true" : "false"}>{data.blockedCount} blocked</span>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>Journey</th>
            <th>Customer</th>
            <th>Current stage</th>
            <th>Status</th>
            <th>Evidence</th>
            <th>Blocked</th>
            <th>Last signal</th>
            <th>Source</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((j) => (
            <tr key={j.id}>
              <td><Link href={`/order-journey/${j.id}`}>{j.id.slice(0, 8)}</Link></td>
              <td>{j.customerDisplayName ?? j.customerAccountId ?? "—"}</td>
              <td>{j.currentStage}</td>
              <td>{j.currentStatus}</td>
              <td><EvidenceBadge level={j.evidenceLevel} /></td>
              <td><BlockedBadge blocked={j.blocked} /></td>
              <td>{j.lastSignalAt ?? "—"}</td>
              <td>{j.sourceType}</td>
              <td>{j.updatedAt}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="risk-note">
        {data.total} total{data.partial ? ` — showing the ${data.previewLimit} most recently updated` : ""}. Derived read model; statuses come from backend projection only.
      </p>
    </section>
  );
}
