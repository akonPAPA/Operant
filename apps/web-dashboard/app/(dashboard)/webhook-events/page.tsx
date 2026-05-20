import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Webhook Events">
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Provider</th><th>External Event</th><th>Signature</th><th>Status</th><th>Received</th></tr></thead>
          <tbody><tr><td>Email / Telegram / WhatsApp</td><td>Pending</td><td>Placeholder</td><td>Accepted or rejected</td><td>Timestamp</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}