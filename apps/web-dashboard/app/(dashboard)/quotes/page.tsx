import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Draft Quote Workspace">
      <section className="panel">
        <h2>Internal Draft Quotes</h2>
        <p>Draft quotes are internal OrderPilot workflow records created from validation output.</p>
        <p className="risk-note">Internal draft only - does not send to ERP/customer.</p>
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Quote</th><th>Status</th><th>Customer</th><th>Total</th><th>Approval</th></tr></thead>
          <tbody><tr><td><Link href="/quotes/latest">Latest</Link></td><td>NEEDS_REVIEW</td><td>Pending</td><td>Pending</td><td>Internal only</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
