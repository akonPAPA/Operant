import { DashboardShell } from "@/components/dashboard-shell";

const providers = ["Email", "File Upload", "API", "Telegram", "WhatsApp", "Meta Messenger", "Viber", "WeChat"];
const diagnostics = ["WEBHOOK_VERIFICATION_DISABLED", "SECRET_MISSING", "READ_ONLY_MODE"];

export default function Page() {
  return (
    <DashboardShell title="Settings / Channels">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Universal channel intake</h2>
          <p>Customer communication channels are tenant-scoped, DRAFT and READ_ONLY by default, and adapter-ready for Stage 13 security checks. Webhooks normalize inbound payloads only; they do not create quotes, orders, replies, commands, or ERP writes.</p>
        </section>
        <section className="page-grid">
          {providers.map((provider) => (
            <article className="panel" key={provider}>
              <h2>{provider}</h2>
              <dl className="metric-list">
                <div><dt>Status</dt><dd>DRAFT until activated</dd></div>
                <div><dt>Mode</dt><dd>READ_ONLY</dd></div>
                <div><dt>Secret</dt><dd>secretConfigured only; raw secrets hidden</dd></div>
                <div><dt>Last health check</dt><dd>Structured diagnostics from core-api</dd></div>
                <div><dt>Actions</dt><dd>activate / pause / disable / health-check</dd></div>
              </dl>
              <div className="tag-row">
                {diagnostics.map((diagnostic) => <span className="status-pill warning" key={diagnostic}>{diagnostic}</span>)}
              </div>
              <p className="risk-note">Provider webhooks use explicit verification modes. Local verification relaxation is labeled as DISABLED_FOR_LOCAL_DEV.</p>
            </article>
          ))}
        </section>
      </div>
    </DashboardShell>
  );
}
