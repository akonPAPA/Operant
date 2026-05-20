import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Omnichannel Inbox">
      <section className="panel">
        <h2>Unified intake queue</h2>
        <p>Stage 9C demo intake centers on a Telegram RFQ, unknown-message handoff, and document-ready validation story. External channels remain controlled stubs.</p>
      </section>
      <section className="panel filter-row">
        <span>Channel</span>
        <span>Status</span>
        <span>Date</span>
        <span>Type</span>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Received</th><th>Channel</th><th>Kind</th><th>Status</th></tr></thead>
          <tbody>
            <tr><td>Demo fixture</td><td>Telegram</td><td>RFQ request</td><td>Requires review</td></tr>
            <tr><td>Demo fixture</td><td>Telegram</td><td>Unknown message</td><td>Human handoff</td></tr>
            <tr><td>Planned</td><td>PDF / Excel</td><td>Supplier or customer document</td><td>Validation-ready</td></tr>
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
