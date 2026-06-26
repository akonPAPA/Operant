import { getOperatorFulfillmentTimeline } from "@/lib/order-journey-api";
import type { OperatorTimelineEntry } from "@/lib/order-journey-api";
import { BlockedBadge, EvidenceBadge } from "./order-journey-status-badge";

// OP-CAP-47B — internal operator fulfillment timeline surface. Consumes the read-only, tenant-scoped
// OP-CAP-47A endpoint (`GET /api/v1/order-journeys/{id}/operator-timeline`) and renders a business-facing
// view: a journey summary card plus its signal-derived timeline. Strictly display-only — this surface
// submits nothing and mutates no milestone/signal/order/approval/execution state.
//
// Safety: it renders only the operator-safe fields the backend returns. It never surfaces raw payload
// refs, source refs, idempotency keys, tenant ids, audit ids, internal entity ids, storage refs, or raw
// backend error bodies, and it never dumps the raw JSON response as the primary UI.
export async function OperatorFulfillmentTimeline({ id }: Readonly<{ id: string }>) {
  const { data, error } = await getOperatorFulfillmentTimeline(id);

  if (!data) {
    // Error state — the API client already maps backend failures to a safe message and drains raw
    // bodies; we never render stack traces or internal technical details to the operator.
    return (
      <section className="panel">
        <h2>Fulfillment timeline</h2>
        <p className="muted-copy">
          {error ?? "Fulfillment timeline is unavailable right now. Please retry shortly."}
        </p>
      </section>
    );
  }

  // Order strictly by the backend-assigned sequence; never re-derive ordering from timestamps.
  const entries = [...data.timeline].sort((a, b) => a.sequence - b.sequence);

  return (
    <>
      <section className="panel">
        <div className="status-row">
          <h2>Fulfillment timeline</h2>
          <BlockedBadge blocked={data.blocked} />
          {data.returnRequested ? (
            <span className="severity-badge" data-return-requested="true">
              Return requested
            </span>
          ) : null}
        </div>
        <div className="kpi-grid">
          <Cell label="Current status" value={data.currentStatus} />
          <Cell label="Current stage" value={data.currentStage} />
          <Cell label="Risk level" value={data.riskLevel} />
          <Cell label="Signals" value={String(data.signalCount)} />
        </div>
        <dl className="detail-list">
          <div>
            <dt>Latest signal received</dt>
            <dd>{formatTimestamp(data.latestSignalReceivedAt)}</dd>
          </div>
        </dl>
        {data.returnRequested ? (
          <p className="risk-note">
            A return has been requested on this order. Review the timeline and follow your returns process.
          </p>
        ) : null}
      </section>

      <section className="panel table-panel">
        <h2>Signal timeline</h2>
        {entries.length === 0 ? (
          <p className="muted-copy">No fulfillment signals recorded for this journey yet.</p>
        ) : (
          <ol className="detail-list" aria-label="Operator fulfillment timeline">
            {entries.map((entry) => (
              <TimelineRow key={entry.sequence} entry={entry} />
            ))}
          </ol>
        )}
      </section>
    </>
  );
}

function TimelineRow({ entry }: Readonly<{ entry: OperatorTimelineEntry }>) {
  return (
    <li>
      <div className="status-row">
        <strong>{entry.label}</strong>
        {entry.status ? <span className="status-pill" data-state={entry.status}>{entry.status}</span> : null}
        <EvidenceBadge level={entry.evidenceLevel} />
        <span className="muted-copy">{entry.sourceType}</span>
        {entry.customerVisible ? (
          <span className="status-pill" data-customer="true">Customer-visible</span>
        ) : (
          <span className="muted-copy">Internal only</span>
        )}
      </div>
      <dd>
        Received {formatTimestamp(entry.receivedAt)}
        {entry.processedAt ? ` · Processed ${formatTimestamp(entry.processedAt)}` : ""}
      </dd>
    </li>
  );
}

// Loading state — a stable skeleton shown while the timeline server component awaits the read.
// Mirrors the summary card + list layout so the surface does not jump on load.
export function OperatorFulfillmentTimelineSkeleton() {
  return (
    <>
      <section className="panel" aria-busy="true" aria-label="Loading fulfillment timeline">
        <h2>Fulfillment timeline</h2>
        <p className="muted-copy">Loading fulfillment timeline…</p>
      </section>
      <section className="panel">
        <h2>Signal timeline</h2>
        <p className="muted-copy">Loading signals…</p>
      </section>
    </>
  );
}

// Render timestamps defensively: a missing timestamp becomes an em dash, and a non-string value is
// never coerced into a misleading label. The backend already returns ISO-8601 strings.
function formatTimestamp(value: string | null | undefined): string {
  if (typeof value !== "string" || value.trim() === "") {
    return "—";
  }
  return value;
}

function Cell({ label, value }: Readonly<{ label: string; value: string }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
