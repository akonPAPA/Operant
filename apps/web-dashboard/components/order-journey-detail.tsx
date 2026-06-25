import { getOrderJourney } from "@/lib/order-journey-api";
import { OrderJourneyTimeline } from "./order-journey-timeline";
import { FulfillmentSignalPanel } from "./fulfillment-signal-panel";
import { BlockedBadge } from "./order-journey-status-badge";
import { OrderJourneyTrackingLinkButton } from "./order-journey-tracking-link-button";

// OP-CAP-22 — Order Journey detail. Separates internal status from customer-visible status, shows
// the milestone timeline, recent events, fulfillment signals, blocks, and an honest payment state.
export async function OrderJourneyDetail({ id }: Readonly<{ id: string }>) {
  const { data, error } = await getOrderJourney(id);

  if (!data) {
    return (
      <section className="panel">
        <h2>Order journey</h2>
        <p className="muted-copy">{error ?? "Order journey not found."}</p>
      </section>
    );
  }

  return (
    <>
      <section className="panel">
        <div className="status-row">
          <h2>Journey {data.id.slice(0, 8)}</h2>
          <BlockedBadge blocked={data.blocked} />
        </div>
        <div className="kpi-grid">
          <Cell label="Source" value={`${data.sourceType}`} />
          <Cell label="Customer" value={data.customerDisplayName ?? data.customerAccountId ?? "—"} />
          <Cell label="Current stage" value={data.currentStage} />
          <Cell label="Risk level" value={data.riskLevel} />
        </div>
        <dl className="detail-list">
          <div>
            <dt>Internal status</dt>
            <dd>{data.internalStatus}</dd>
          </div>
          <div>
            <dt>Customer-visible status</dt>
            <dd>{data.customerVisibleStatus}</dd>
          </div>
          <div>
            <dt>Payment status</dt>
            <dd>{data.paymentStatusAvailable ? "Available" : "Payment status unavailable"}</dd>
          </div>
          <div>
            <dt>Projection</dt>
            <dd>{projectionSourceLabel(data.projectionSource)}</dd>
          </div>
        </dl>
      </section>

      <section className="panel">
        <h2>Milestones &amp; timeline</h2>
        <OrderJourneyTimeline milestones={data.milestones} />
      </section>

      <section className="panel">
        <h2>Latest events</h2>
        {data.recentEvents.length === 0 ? (
          <p className="muted-copy">No journey events yet.</p>
        ) : (
          <ul className="detail-list">
            {data.recentEvents.map((e, i) => (
              <li key={`${e.eventType}-${e.occurredAt}-${i}`}>
                <strong>{e.eventType}</strong> — {e.message} <span className="muted-copy">({e.evidenceLevel}, {e.actorType})</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <FulfillmentSignalPanel signals={data.fulfillmentSignals} connected={data.fulfillmentTrackingConnected} />

      <section className="panel">
        <h2>Customer-safe status preview</h2>
        <p>{data.customerVisibleStatus}</p>
        <p className="risk-note">Internal-only status, risk level, and blocks are never exposed on customer-facing surfaces.</p>
      </section>

      <OrderJourneyTrackingLinkButton journeyId={data.id} />
    </>
  );
}

// OP-CAP-23/24 — honestly reports how the projection was obtained. "READY" means it was prepared by the
// event/outbox projector from a real backend business event (the production path, OP-CAP-24 source hooks);
// "ON_READ_FALLBACK" means it was materialized during this read as the documented temporary fallback while
// the projector catches up. Status authority is the backend — the frontend never invents journey status.
function projectionSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case "READY":
      return "Prepared by projector (event-backed)";
    case "ON_READ_FALLBACK":
      return "Refreshed on read (projector pending)";
    default:
      return "—";
  }
}

function Cell({ label, value }: Readonly<{ label: string; value: string }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
