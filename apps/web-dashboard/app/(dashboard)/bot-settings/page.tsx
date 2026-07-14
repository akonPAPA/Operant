import { BotSettingsWorkspace } from "@/components/bot-settings-workspace";
import { DashboardShell } from "@/components/dashboard-shell";
import { getBotRuntimeSettings, listBotConversationDetails, listBotHandoffs } from "@/lib/server/bot-runtime-api.server";

export default async function BotSettingsPage() {
  const [settings, handoffs, details] = await Promise.all([
    getBotRuntimeSettings(),
    listBotHandoffs(),
    listBotConversationDetails()
  ]);

  const error = [settings.error, handoffs.error, details.error].filter(Boolean).join(" ");

  return (
    <DashboardShell title="Bot Settings">
      <section className="panel">
        <h2>Controlled Bot Runtime</h2>
        <p>Telegram business messages are handled by deterministic, policy-gated backend flows and routed to operator handoff when confidence, authorization, or risk checks fail.</p>
        <p className="risk-note">The bot cannot approve quotes, approve orders, approve discounts, mutate customer/product/price/inventory data, or trigger ERP writes.</p>
      </section>
      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}
      <BotSettingsWorkspace initialSettings={settings.data} initialHandoffs={handoffs.data ?? []} initialDetails={details.data ?? []} />
    </DashboardShell>
  );
}
