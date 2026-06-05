import { DashboardShell } from "@/components/dashboard-shell";
import { getChannelBotEvents } from "@/lib/channel-bot-api";

// OP-CAP-06A Messenger Chatbot Integration Layer.
// Read-only operator surface: shows how verified channel intake became a controlled bot
// conversation / RFQ / handoff. No secrets, raw tokens, or raw provider payloads are shown,
// and no mutation/send/approve actions are offered.

function linkLabel(event: { botConversationId?: string; botRuntimeStatus?: string }) {
  if (event.botConversationId) {
    return `Bot conversation ${event.botConversationId.slice(0, 8)}…`;
  }
  return event.botRuntimeStatus ?? "Not bridged";
}

export default async function Page() {
  const { data: events, error } = await getChannelBotEvents();

  return (
    <DashboardShell title="Messenger Bridge">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Controlled messenger → bot runtime bridge</h2>
          <p>
            Verified, tenant-scoped channel connections normalize inbound messenger events and drive the
            controlled bot runtime. The bot classifies intent, applies policy, and can only create reviewable
            RFQ / draft / handoff records. It cannot approve quotes, orders, or discounts, mutate inventory,
            price, or customer data, or send outbound messages.
          </p>
          <div className="tag-row">
            <span className="status-pill ok">externalExecution=DISABLED</span>
            <span className="status-pill ok">Telegram-first (provider-agnostic ready)</span>
            <span className="status-pill ok">Secrets stored by reference only</span>
          </div>
          <p className="risk-note">
            Raw bot tokens, secret references, and raw provider payloads are never displayed. Duplicate provider
            deliveries are ignored and never create duplicate conversations, RFQs, or handoffs.
          </p>
        </section>

        {error ? (
          <section className="empty-state">
            <h2>Backend data unavailable</h2>
            <p>{error}</p>
          </section>
        ) : null}

        <section className="panel table-panel">
          <h2>Channel events bridged to the bot runtime</h2>
          <table className="data-table">
            <thead>
              <tr>
                <th>Provider</th>
                <th>Received</th>
                <th>Sender</th>
                <th>Normalized message</th>
                <th>Event status</th>
                <th>Verification</th>
                <th>Bot runtime status</th>
                <th>Linked bot conversation</th>
              </tr>
            </thead>
            <tbody>
              {events.length === 0 ? (
                <tr>
                  <td colSpan={8}>No bridged messenger events yet. Verified inbound channel events will appear here once received.</td>
                </tr>
              ) : (
                events.map((event) => (
                  <tr key={event.id}>
                    <td>{event.providerType}</td>
                    <td>{event.receivedAt}</td>
                    <td>{event.sourceActorExternalId ?? "n/a"}</td>
                    <td>{event.normalizedText ?? "n/a"}</td>
                    <td><span className="status-pill">{event.status}</span></td>
                    <td>{event.verificationStatus ?? "n/a"}</td>
                    <td>{event.botRuntimeStatus ?? "PENDING"}</td>
                    <td>{linkLabel(event)}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <p className="risk-note">
            This view is read-only. It surfaces why a messenger message became an RFQ, draft, or operator handoff.
            It offers no approve, reject, retry, send, connector, or ERP/1C actions.
          </p>
        </section>
      </div>
    </DashboardShell>
  );
}
