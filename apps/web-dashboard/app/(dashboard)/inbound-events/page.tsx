import { DashboardShell } from "@/components/dashboard-shell";
import { getInboundEvents } from "@/lib/server/intake-api.server";
import { AccessDeniedState, EmptyState, ErrorState } from "@/components/page-states";
import Link from "next/link";

export default async function Page() {
  const { data: events, error, code } = await getInboundEvents();
  // Authorization denial is distinct from a dependency/read failure. `error`/`code` are already
  // redacted public values — no raw Core body, stack trace, internal URL, or identifier is rendered.
  const accessDenied = code === "ACCESS_DENIED" || code === "AUTH_REQUIRED";

  return (
    <DashboardShell title="Inbound Events">
      {error && accessDenied ? (
        <AccessDeniedState description="You do not have access to tenant inbound events." />
      ) : error ? (
        <ErrorState
          title="Backend data unavailable"
          description={error}
          action={
            // Idempotent read retry: reloads the same GET route; no mutation, no request loop.
            <Link className="secondary-button table-link-button" href="/inbound-events">
              Retry
            </Link>
          }
        />
      ) : events.length === 0 ? (
        <EmptyState
          title="No inbound events yet"
          description="No inbound channel events have been received for this tenant."
          note="Inbound channel messages are normalized and stored only — they never execute commands or create commerce records directly."
        />
      ) : (
        <section className="panel table-panel">
          <table className="data-table">
            <thead><tr><th>Source</th><th>Event type</th><th>External ID</th><th>Status</th><th>Fingerprint</th><th>Raw stored</th></tr></thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id}>
                  <td>{event.source}</td>
                  <td>{event.eventType}</td>
                  <td>{event.externalEventId ?? "n/a"}</td>
                  <td>{event.status}</td>
                  <td>{event.fingerprintSha256?.slice(0, 12) ?? "n/a"}</td>
                  <td>{event.rawPayloadStored ? "yes" : "no"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="risk-note">Inbound channel messages are normalized and stored only. Verification failure rejects storage, and accepted payloads still do not execute commands or create commerce records directly.</p>
        </section>
      )}
    </DashboardShell>
  );
}
