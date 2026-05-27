import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Analytics">
      <div className="page-grid">
        <section className="panel"><h2>Intake Volume</h2><p>Inbound documents, channel messages, channel breakdown, duplicate/replay signals, and processing backlog.</p></section>
        <section className="panel"><h2>Extraction</h2><p>Extraction run statuses, average confidence, low-confidence result count, and extracted line-item volume.</p></section>
        <section className="panel"><h2>Validation</h2><p>Validation status counts, top issue codes, approval requirement reasons, blocked runs, and needs-review runs.</p></section>
        <section className="panel"><h2>Review Backlog</h2><p>Review cases by status, open backlog, escalations, correction requests, and approved-for-next-stage counts.</p></section>
        <section className="panel"><h2>Bot Runtime</h2><p>Conversation statuses, intent counts, handoffs, unknown intents, and needs-review conversations.</p></section>
        <section className="panel"><h2>Workflow Health</h2><p>Processing job statuses, failed jobs, stale jobs, recent audit events, and operator action activity.</p></section>
      </div>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Read model</th><th>Endpoint</th><th>Scope</th><th>Boundary</th></tr></thead>
          <tbody>
            <tr><td>Overview</td><td>/api/v1/analytics/overview</td><td>Tenant-scoped aggregate</td><td>Read-only</td></tr>
            <tr><td>Top issue codes</td><td>/api/v1/analytics/validation</td><td>Status and issue counts</td><td>No raw uploads or message bodies</td></tr>
            <tr><td>Channel breakdown</td><td>/api/v1/analytics/intake</td><td>Documents and messages by channel</td><td>No connector or workflow execution</td></tr>
            <tr><td>Automation readiness</td><td>/api/v1/analytics/overview</td><td>Operational indicators</td><td>Visibility only</td></tr>
          </tbody>
        </table>
      </section>
      <section className="panel">
        <h2>Safety Boundary</h2>
        <p className="risk-note">Analytics reads tenant-scoped workflow counts only. It does not create quotes/orders, approve reviews, send customer messages, mutate master data, or execute connectors.</p>
      </section>
    </DashboardShell>
  );
}
