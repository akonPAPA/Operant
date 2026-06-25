import { getPublicOrderTracking, PublicTrackingMilestone } from "@/lib/public-order-tracking-api";
import { EvidenceBadge, MilestoneStateBadge } from "./order-journey-status-badge";

// OP-CAP-46E — public, read-only customer order tracking view.
//
// Rendered on the server: the token arrives via the route param, is used only to fetch, and is
// NEVER written to browser storage, logged, or sent to analytics. The page consumes the redacted
// backend PublicOrderTrackingView and renders ONLY customer-safe fields (status label, milestone
// label/state/evidence, occurred/estimated timestamps, tracking-connected indicator, last
// updated). It carries no mutation controls and no internal identifiers.
export async function PublicOrderTracking({ token }: Readonly<{ token: string }>) {
  const result = await getPublicOrderTracking(token);

  if (!result.ok) {
    return (
      <main className="public-tracking">
        <section className="panel">
          <h1>Order tracking</h1>
          {result.kind === "invalid" ? (
            <p className="muted-copy">
              This tracking link is invalid or has expired. Please ask your supplier for an
              up-to-date link.
            </p>
          ) : (
            <p className="muted-copy">
              Order tracking is temporarily unavailable. Please try again in a few minutes.
            </p>
          )}
        </section>
      </main>
    );
  }

  const { data } = result;

  return (
    <main className="public-tracking">
      <section className="panel">
        <div className="status-row">
          <h1>Order tracking</h1>
          <span className="status-pill" data-status="true">{data.statusLabel}</span>
        </div>
        <p className="muted-copy">
          {data.fulfillmentTrackingConnected
            ? "Live fulfillment tracking is connected for this order."
            : "Fulfillment tracking is not connected yet — milestones reflect the latest confirmed status."}
        </p>
      </section>

      <section className="panel">
        <h2>Progress</h2>
        {data.milestones.length === 0 ? (
          <p className="muted-copy">No tracking milestones are available yet.</p>
        ) : (
          <ol className="detail-list" aria-label="Order tracking timeline">
            {data.milestones.map((m, i) => (
              <li key={`${m.milestoneLabel}-${i}`}>
                <div className="status-row">
                  <strong>{m.milestoneLabel}</strong>
                  <MilestoneStateBadge state={m.milestoneState} />
                  <EvidenceBadge level={m.evidenceLevel} />
                </div>
                <dd>{milestoneTiming(m)}</dd>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="panel">
        <p className="muted-copy">Last updated {formatTimestamp(data.generatedAt)}.</p>
      </section>
    </main>
  );
}

// Honest, customer-safe timing line. Never fabricates a timestamp: shows the confirmed time when
// present, otherwise an estimate when present, otherwise an explicit "awaiting update".
function milestoneTiming(m: PublicTrackingMilestone): string {
  if (m.occurredAt) {
    return `Completed ${formatTimestamp(m.occurredAt)}`;
  }
  if (m.estimatedAt) {
    return `Estimated ${formatTimestamp(m.estimatedAt)}`;
  }
  return "Awaiting update";
}

// Formats an ISO-8601 timestamp deterministically (UTC) on the server. Falls back to the raw
// value if it is not parseable — it never invents or shifts a date.
function formatTimestamp(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toISOString();
}
