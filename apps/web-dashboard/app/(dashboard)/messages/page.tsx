import { DashboardShell } from "@/components/dashboard-shell";
import { getIntakeMessages } from "@/lib/server/intake-api.server";
import Link from "next/link";

export default async function Page({ searchParams }: Readonly<{ searchParams: Promise<{ channel?: string; status?: string }> }>) {
  const { data: messages, error } = await getIntakeMessages();
  const filters = await searchParams;
  const filteredMessages = messages
    .filter((message) => !filters.channel || filters.channel === "ALL" || message.channel === filters.channel)
    .filter((message) => !filters.status || filters.status === "ALL" || message.status === filters.status);

  return (
    <DashboardShell title="Messages">
      <section className="panel filter-row">
        {["ALL", "API", "EMAIL", "TELEGRAM", "WHATSAPP"].map((channel) => (
          <a href={`/messages?channel=${channel}&status=${filters.status ?? "ALL"}`} key={channel}>{channel}</a>
        ))}
        {["ALL", "QUEUED", "NEEDS_REVIEW", "FAILED", "COMPLETED"].map((status) => (
          <a href={`/messages?channel=${filters.channel ?? "ALL"}&status=${status}`} key={status}>{status}</a>
        ))}
      </section>
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Channel</th><th>Sender</th><th>Conversation</th><th>Status</th><th>Text</th><th>Received</th></tr></thead>
          <tbody>
            {filteredMessages.length === 0 ? <tr><td colSpan={6}>No messages match the current filters.</td></tr> : filteredMessages.map((message) => (
              <tr key={message.id}>
                <td><Link href={`/messages/${message.id}`}>{message.channel}</Link></td>
                <td>{message.senderHandle ?? "Unknown"}</td>
                <td>{message.conversationId ?? message.externalMessageId ?? "None"}</td>
                <td>{message.status}</td>
                <td>{message.textContent ?? ""}</td>
                <td>{new Date(message.receivedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
