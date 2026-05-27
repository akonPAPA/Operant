import { DashboardShell } from "@/components/dashboard-shell";
import { getInboundEvents } from "@/lib/intake-api";

export default async function Page() {
  const { data: events, error } = await getInboundEvents();
  return (
    <DashboardShell title="Inbound Events">
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Source</th><th>Event type</th><th>External ID</th><th>Status</th><th>Fingerprint</th><th>Payload key</th></tr></thead>
          <tbody>
            {events.length === 0 ? <tr><td colSpan={6}>No inbound events received.</td></tr> : events.map((event) => (
              <tr key={event.id}>
                <td>{event.source}</td>
                <td>{event.eventType}</td>
                <td>{event.externalEventId ?? "n/a"}</td>
                <td>{event.status}</td>
                <td>{event.fingerprintSha256?.slice(0, 12) ?? "n/a"}</td>
                <td>{event.rawPayloadStorageKey ?? "n/a"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="risk-note">Inbound channel messages are normalized and stored only. Verification failure rejects storage, and accepted payloads still do not execute commands or create commerce records directly.</p>
      </section>
    </DashboardShell>
  );
}
