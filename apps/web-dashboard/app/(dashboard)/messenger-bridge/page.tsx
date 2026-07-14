import { DashboardShell } from "@/components/dashboard-shell";
import {
  DEFAULT_BRIDGE_EVENT_LIMIT,
  getChannelBotBridgeStatus,
  getChannelBotEvents
} from "@/lib/server/channel-bot-api.server";

// OP-CAP-06A Messenger Chatbot Integration Layer.
// Read-only operator surface: shows how verified channel intake became a controlled bot
// conversation / RFQ / handoff. No secrets, raw tokens, or raw provider payloads are shown,
// and no mutation/send/approve actions are offered. The events list is bounded to a recent
// window; the backend independently clamps the limit.

function linkLabel(event: { botConversationId?: string; botRuntimeStatus?: string }) {
  if (event.botConversationId) {
    return `Bot conversation ${event.botConversationId.slice(0, 8)}…`;
  }
  return event.botRuntimeStatus ?? "Not bridged";
}

function flowLabel(value: string) {
  return value.replace(/_/g, " ").toLowerCase();
}

export default async function Page() {
  const [{ data: events, error }, { data: status, error: statusError }] = await Promise.all([
    getChannelBotEvents(DEFAULT_BRIDGE_EVENT_LIMIT),
    getChannelBotBridgeStatus(DEFAULT_BRIDGE_EVENT_LIMIT)
  ]);

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

        {error || statusError ? (
          <section className="empty-state">
            <h2>Backend data unavailable</h2>
            <p>{error ?? statusError}</p>
          </section>
        ) : null}

        <section className="panel">
          <h2>Bridge status</h2>
          <div className="tag-row">
            <span className="status-pill ok">externalExecution={status.externalExecution}</span>
            <span className="status-pill">Recent window: {status.recentWindowLimit}</span>
            <span className="status-pill">Recent events: {status.recentEventCount}</span>
            <span className="status-pill done">Bridged to bot: {status.bridgedToBotCount}</span>
            <span className="status-pill warning">Pending / not bridged: {status.pendingOrUnbridgedCount}</span>
          </div>
          {status.supportedFlows.length > 0 ? (
            <>
              <p className="muted-copy">Supported safe flows</p>
              <div className="tag-row">
                {status.supportedFlows.map((flow) => (
                  <span key={flow} className="status-pill ok">{flowLabel(flow)}</span>
                ))}
              </div>
            </>
          ) : null}
          {status.forbiddenActions.length > 0 ? (
            <>
              <p className="muted-copy">Never performed by the bridge</p>
              <div className="tag-row">
                {status.forbiddenActions.map((action) => (
                  <span key={action} className="status-pill warning">{flowLabel(action)}</span>
                ))}
              </div>
            </>
          ) : null}
        </section>

        <section className="panel table-panel">
          <h2>Recent channel events bridged to the bot runtime</h2>
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
                  <td colSpan={8}>No bridged messenger events in the recent window. Verified inbound channel events will appear here once received.</td>
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
            This view is read-only and shows the most recent {status.recentWindowLimit} events. It surfaces why a
            messenger message became an RFQ, draft, or operator handoff. It offers no approve, reject, retry, send,
            connector, or ERP/1C actions.
          </p>
        </section>
      </div>
    </DashboardShell>
  );
}
