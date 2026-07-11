import { BotRuntimeConfigWorkspace } from "@/components/bot-runtime-config-workspace";
import { DashboardShell } from "@/components/dashboard-shell";
import { getBotRuntimeConfiguration, getBotRuntimeConfigurations } from "@/lib/server/bot-runtime-config-api.server";

// OP-CAP-06B.1 Interactive Bot Runtime Configuration editor.
// Server loader: fetches the tenant-scoped, bot-capable channel connections and the first connection's
// controlled configuration, then renders the interactive editor. The editor exposes deterministic
// policy only — never bot tokens, secret references, or provider credentials — and offers no
// outbound-send, approve, or external-execution actions.

export default async function Page() {
  const { data: connections, error } = await getBotRuntimeConfigurations();
  const firstConnectionId =
    connections.find((connection) => connection.configured)?.channelConnectionId ??
    connections[0]?.channelConnectionId;

  let initialConfig = null;
  let detailError: string | undefined;
  if (firstConnectionId) {
    const result = await getBotRuntimeConfiguration(firstConnectionId);
    initialConfig = result.data;
    detailError = result.error;
  }
  const initialError = [error, detailError].filter(Boolean).join(" ") || undefined;

  return (
    <DashboardShell title="Bot Runtime Configuration">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Controlled bot runtime configuration</h2>
          <p>
            Configure what the bot is allowed to do per channel connection. Configuration can only constrain bot
            behavior. The bot cannot approve final orders, quotes, or discounts, and cannot write to ERP/1C or to
            customer, product, price, or inventory master data. Backend runtime and deterministic validation
            remain the final authority.
          </p>
          <div className="tag-row">
            <span className="status-pill ok">externalExecution=DISABLED</span>
            <span className="status-pill ok">Telegram-first (provider-agnostic ready)</span>
            <span className="status-pill ok">No tokens or secrets shown or edited</span>
          </div>
          <p className="risk-note">
            RFQ/order work remains draft/review-only. External writes require separate integration approval and are
            not part of this screen. Raw bot tokens, secret references, and provider credentials are never displayed
            or editable here.
          </p>
        </section>

        <BotRuntimeConfigWorkspace
          initialConnections={connections}
          initialConfig={initialConfig}
          initialError={initialError}
        />
      </div>
    </DashboardShell>
  );
}
