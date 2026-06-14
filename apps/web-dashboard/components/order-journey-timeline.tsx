import type { OrderJourneyMilestone } from "@/lib/order-journey-api";
import { EvidenceBadge, MilestoneStateBadge } from "./order-journey-status-badge";

// OP-CAP-22 — ordered milestone timeline. Each step shows its state and evidence level so verified,
// mirrored, estimated, unknown, and blocked steps are visually distinct. No fabricated location data.
export function OrderJourneyTimeline({ milestones }: Readonly<{ milestones: OrderJourneyMilestone[] }>) {
  if (milestones.length === 0) {
    return <p className="muted-copy">No milestones derived yet.</p>;
  }
  return (
    <ol className="detail-list" aria-label="Order journey timeline">
      {milestones.map((m) => (
        <li key={m.milestoneCode}>
          <div className="status-row">
            <strong>{m.milestoneLabel}</strong>
            <MilestoneStateBadge state={m.milestoneState} />
            <EvidenceBadge level={m.evidenceLevel} />
            {m.customerVisible ? <span className="status-pill" data-customer="true">Customer-visible</span> : <span className="muted-copy">Internal only</span>}
          </div>
          <dd>{m.occurredAt ? `Occurred ${m.occurredAt}` : m.estimatedAt ? `Estimated ${m.estimatedAt}` : "No timestamp"}</dd>
        </li>
      ))}
    </ol>
  );
}
