import { DashboardShell } from "@/components/dashboard-shell";

const providers = ["Email", "File Upload", "API", "Telegram", "WhatsApp", "Meta Messenger", "Viber", "WeChat"];

export default function Page() {
  return (
    <DashboardShell title="Settings / Channels">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Universal channel intake</h2>
          <p>Customer communication channels are tenant-scoped, read-only by default, and adapter-ready in Stage 12. Webhooks normalize inbound payloads but do not create quotes, orders, replies, or ERP writes.</p>
        </section>
        <section className="page-grid">
          {providers.map((provider) => (
            <article className="panel" key={provider}>
              <h2>{provider}</h2>
              <dl className="metric-list">
                <div><dt>Status</dt><dd>Adapter-ready stub</dd></div>
                <div><dt>Mode</dt><dd>READ_ONLY</dd></div>
                <div><dt>Health</dt><dd>Manual test via API</dd></div>
              </dl>
              <p className="risk-note">No external sending, bot command execution, or business-table mutation is enabled.</p>
            </article>
          ))}
        </section>
      </div>
    </DashboardShell>
  );
}
