import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Reconciliation">
      <div className="page-grid">
        <section className="panel"><h2>Canonical demo mismatch</h2><p>Opening 150, sold 34, expected 116, actual 100, mismatch -16.</p></section>
        <section className="panel"><h2>Severity</h2><p><span className="severity-badge">HIGH</span></p></section>
        <section className="panel"><h2>Operator status</h2><p>Open case until a user resolves or marks it through core-api.</p></section>
      </div>
      <section className="panel table-panel action-panel">
        <table className="data-table">
          <thead><tr><th>Product</th><th>Location</th><th>Expected</th><th>Actual</th><th>Mismatch</th><th>Likely causes</th></tr></thead>
          <tbody><tr><td>Toyota Camry 2018 brake pads</td><td>Almaty main warehouse</td><td>116</td><td>100</td><td>-16</td><td>Unposted warehouse issue, counting variance, delayed sales posting</td></tr></tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
