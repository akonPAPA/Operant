import { DashboardShell } from "@/components/dashboard-shell";

export default function BotConversationsPage() {
  return (
    <DashboardShell title="Bot / Conversations">
      <div className="page-grid">
        <section className="panel">
          <h2>RFQ classification</h2>
          <p>Demo Telegram RFQ text is classified as RFQ_REQUEST and creates an internal review draft through core-api.</p>
        </section>
        <section className="panel">
          <h2>Human handoff</h2>
          <p>Unknown or ambiguous messages require human review instead of attempting autonomous selling.</p>
        </section>
        <section className="panel">
          <h2>External messaging boundary</h2>
          <p>No real Telegram calls, no customer-visible business replies, and no final quote or order creation happen here.</p>
        </section>
      </div>
      <section className="panel table-panel action-panel">
        <table className="data-table">
          <thead><tr><th>Demo message</th><th>Intent</th><th>Outcome</th><th>Trust control</th></tr></thead>
          <tbody>
            <tr><td>Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty.</td><td>RFQ_REQUEST</td><td>Internal RFQ draft</td><td>Human review required</td></tr>
            <tr><td>Can you check the thing we discussed last time?</td><td>UNKNOWN</td><td>Handoff</td><td>No automated business action</td></tr>
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
