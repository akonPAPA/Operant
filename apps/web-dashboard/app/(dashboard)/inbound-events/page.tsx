import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Inbound Events">
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Provider</th><th>Status</th><th>Verification</th><th>Normalized text</th><th>Received</th><th>Error</th></tr></thead>
          <tbody>
            <tr><td>Telegram / WhatsApp / WeChat</td><td>NORMALIZED</td><td>ACCEPTED / SKIPPED_LOCAL_DEV</td><td>Untrusted input stored for later routing</td><td>API timestamp</td><td>Shown if failed</td></tr>
          </tbody>
        </table>
        <p className="risk-note">Inbound channel messages are normalized and stored only. Verification failure rejects storage, and accepted payloads still do not execute commands or create commerce records directly.</p>
      </section>
    </DashboardShell>
  );
}
