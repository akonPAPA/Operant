import type { FulfillmentSignal } from "@/lib/order-journey-api";
import { EvidenceBadge } from "./order-journey-status-badge";

// OP-CAP-22 — internal fulfillment signals only. No external carrier feed, no satellite location,
// no real-time tracking map. The source of each signal (INTERNAL / CONNECTOR_MIRROR / IMPORT /
// MANUAL / SYSTEM_DERIVED) is shown so mirrored/estimated signals are never presented as verified truth.
export function FulfillmentSignalPanel({ signals, connected }: Readonly<{ signals: FulfillmentSignal[]; connected: boolean }>) {
  if (!connected || signals.length === 0) {
    return (
      <section className="panel">
        <h2>Fulfillment signals</h2>
        <p className="muted-copy">Fulfillment tracking not connected yet. No carrier, location, or tracking data is shown.</p>
      </section>
    );
  }
  return (
    <section className="panel table-panel">
      <h2>Fulfillment signals</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th>Signal</th>
            <th>Source</th>
            <th>Evidence</th>
            <th>Status</th>
            <th>Received</th>
          </tr>
        </thead>
        <tbody>
          {signals.map((s) => (
            <tr key={s.id}>
              <td>{s.signalType}</td>
              <td>{s.sourceType}</td>
              <td><EvidenceBadge level={evidenceForSource(s.sourceType)} /></td>
              <td>{s.signalStatus ?? "—"}</td>
              <td>{s.receivedAt}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function evidenceForSource(source: string): string {
  switch (source) {
    case "INTERNAL":
      return "VERIFIED";
    case "CONNECTOR_MIRROR":
    case "IMPORT":
      return "MIRRORED";
    case "MANUAL":
      return "MANUAL";
    default:
      return "SYSTEM_DERIVED";
  }
}
