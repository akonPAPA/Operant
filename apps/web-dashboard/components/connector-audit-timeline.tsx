import type { Stage9ConnectorAuditEvent } from "@/lib/stage9-integration-api";

export function ConnectorAuditTimeline({ events }: Readonly<{ events: Stage9ConnectorAuditEvent[] }>) {
  return (
    <section className="panel">
      <h2>Connector Audit Timeline</h2>
      <div className="timeline">
        {events.length === 0 ? (
          <p>No connector audit events yet.</p>
        ) : events.slice(0, 12).map((event) => (
          <div className="timeline-item" key={event.id}>
            <strong>{event.action}</strong>
            <span>{event.entityType} / {event.entityId}</span>
          </div>
        ))}
      </div>
      <p className="risk-note">Audit metadata is connector-specific and must not contain raw credential values or production secrets.</p>
    </section>
  );
}
