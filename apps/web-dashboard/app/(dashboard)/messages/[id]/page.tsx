import { DashboardShell } from "@/components/dashboard-shell";
import { ChannelQuoteConversionPanel } from "@/components/channel-quote-conversion-panel";
import { getIntakeMessage } from "@/lib/server/intake-api.server";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  const { data: message, error } = await getIntakeMessage(id);
  return (
    <DashboardShell title="Message Detail">
      {error ? <section className="empty-state"><h2>Backend data unavailable</h2><p>{error}</p></section> : null}
      <section className="panel">
        <h2>{message.channel ?? "Message"} {id}</h2>
        <dl className="detail-list">
          <div><dt>Status</dt><dd>{message.status ?? "Unknown"}</dd></div>
          <div><dt>Sender</dt><dd>{message.senderHandle ?? "Unknown"}</dd></div>
          <div><dt>Conversation</dt><dd>{message.conversationId ?? message.externalMessageId ?? "None"}</dd></div>
          <div><dt>Received</dt><dd>{message.receivedAt ? new Date(message.receivedAt).toLocaleString() : "Unknown"}</dd></div>
          <div><dt>Text</dt><dd>{message.textContent ?? "No text content"}</dd></div>
        </dl>
      </section>
      <section className="panel">
        <h2>Processing boundary</h2>
        <p>This record is source evidence. Quote preparation runs through tenant-scoped backend validation and does not approve quotes, create orders, or write to ERP.</p>
      </section>
      <ChannelQuoteConversionPanel sourceId={id} sourceType="CHANNEL_MESSAGE" />
    </DashboardShell>
  );
}
