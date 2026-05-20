import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Exception Cockpit">
      <section className="panel">
        <h2>Operator Review Queue</h2>
        <p>Exception cases group validation issues, suggested fixes, approval requirements, substitutes, notes, and timeline events into one review surface.</p>
      </section>
      <section className="panel filter-row">
        <span>Status</span><span>Priority</span><span>Severity</span><span>Assigned user</span><span>Customer</span>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Case</th><th>Status</th><th>Priority</th><th>Severity</th><th>Customer</th><th>Assigned</th></tr></thead>
          <tbody><tr><td><a href="/exception-cockpit/latest">Latest</a></td><td>OPEN</td><td>HIGH</td><td>ERROR</td><td>Pending</td><td>Unassigned</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
