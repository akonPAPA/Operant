import type { Stage9ConnectorAuditEvent } from "@/lib/stage9-integration-api";

export function ConnectorAuditTimeline({ events }: Readonly<{ events: Stage9ConnectorAuditEvent[] }>) {
  return (
    <section className="panel">
      <h2>Connector Audit Timeline</h2>
      <div className="timeline">
        {events.length === 0 ? (
          <p>No connector audit events yet.</p>
        ) : events.slice(0, 12).map((event, index) => (
          <div className="timeline-item" key={`${event.action}-${event.occurredAt}-${index}`}>
            <strong>{event.action}</strong>
            <span>{event.entityType} / {event.occurredAt}</span>
          </div>
        ))}
      </div>
      <p className="risk-note">Only safe audit classifications are shown; raw metadata and internal identifiers are not returned.</p>
    </section>
  );
}
