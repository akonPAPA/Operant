import { DashboardShell } from "@/components/dashboard-shell";

const providers = ["1C", "Excel", "CSV", "Generic Database", "Generic REST API", "NetSuite", "Dynamics 365", "Epicor", "SAP", "Odoo", "QuickBooks", "Other ERP", "Other Accounting", "Other Inventory"];
const diagnostics = ["READ_ONLY_MODE", "SECRET_MISSING", "WRITE_MODE_NOT_ALLOWED"];

export default function Page() {
  return (
    <DashboardShell title="Settings / Integrations">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Business-system connector foundation</h2>
          <p>Integration connections are tenant-scoped and default to DRAFT / READ_ONLY. Stage 13 adds safe secret metadata, structured diagnostics, and a demo ERP read-only pilot without production external writes.</p>
        </section>
        <section className="page-grid">
          {providers.map((provider) => (
            <article className="panel" key={provider}>
              <h2>{provider}</h2>
              <dl className="metric-list">
                <div><dt>Status</dt><dd>DRAFT until activated</dd></div>
                <div><dt>Mode</dt><dd>READ_ONLY / WRITE_DISABLED</dd></div>
                <div><dt>Secret</dt><dd>secretConfigured only; raw secrets hidden</dd></div>
                <div><dt>Connection kind</dt><dd>Configured in core-api</dd></div>
                <div><dt>Last sync</dt><dd>Read-only import history</dd></div>
                <div><dt>Last health check</dt><dd>Structured diagnostics from core-api</dd></div>
                <div><dt>Actions</dt><dd>activate / pause / disable / health-check / read-only sync</dd></div>
              </dl>
              <div className="tag-row">
                {diagnostics.map((diagnostic) => <span className="status-pill warning" key={diagnostic}>{diagnostic}</span>)}
              </div>
              <p className="risk-note">Sync buttons are for read-only pilot imports only. No write-mode UI controls are enabled.</p>
            </article>
          ))}
        </section>
      </div>
    </DashboardShell>
  );
}
