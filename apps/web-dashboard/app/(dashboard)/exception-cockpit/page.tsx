import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Exception Cockpit">
      <section className="panel">
        <h2>Operator Review Queue</h2>
        <p>Review cases group extraction evidence, validation issues, suggested corrective actions, approval requirements, substitutes, internal notes, and audit timeline events into one operator surface.</p>
        <p className="risk-note">Approving here means approved for next stage only. It does not create a quote/order, send a customer message, or write to ERP.</p>
      </section>
      <section className="panel filter-row">
        <span>Status</span><span>Risk</span><span>Issue group</span><span>Approval</span><span>Source</span>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Review case</th><th>Status</th><th>Risk</th><th>Grouped issues</th><th>Approval requirements</th><th>Source</th></tr></thead>
          <tbody><tr><td><Link href="/exception-cockpit/latest">Latest</Link></td><td>REVIEW_REQUIRED</td><td>HIGH</td><td>Inventory, substitution, policy</td><td>Open</td><td>Validation run</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
