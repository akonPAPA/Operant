import { BotConversationsWorkspace } from "@/components/bot-conversations-workspace";
import { DashboardShell } from "@/components/dashboard-shell";
import { listBotConversationDetails } from "@/lib/bot-runtime-api";

export default async function BotConversationsPage() {
  const { data, error } = await listBotConversationDetails();

  return (
    <DashboardShell title="Bot / Conversations">
      <section className="panel">
        <h2>Bot Runtime Lite</h2>
        <p>Telegram-oriented customer messages are captured, classified with deterministic rules, and routed to operator review when policy requires it.</p>
        <p className="risk-note">Phase 7B accepts inbound Telegram webhook payloads at /api/v1/bot-runtime/telegram/webhook. Outbound Telegram replies are not productionized here.</p>
        <p className="risk-note">Customer text is untrusted input. Backend policy owns decisions; the UI only displays results and sends local simulation requests.</p>
      </section>
      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}
      <BotConversationsWorkspace initialDetails={data ?? []} />
    </DashboardShell>
  );
}
