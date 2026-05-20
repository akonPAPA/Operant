import { DashboardShell } from "@/components/dashboard-shell";

export default function Page() {
  return (
    <DashboardShell title="Audit Log">
      <div className="page-grid">
        <section className="panel"><h2>Bot RFQ draft</h2><p>Successful demo RFQ flow records BOT_RFQ_DRAFT_CREATED in the backend audit trail.</p></section>
        <section className="panel"><h2>Reconciliation case</h2><p>Inventory mismatch creation records RECONCILIATION_CASE_CREATED.</p></section>
        <section className="panel"><h2>Trust boundary</h2><p>Audit records explain controlled backend actions. The frontend has no direct database or ERP write path.</p></section>
      </div>
      <section className="panel action-panel">
        <h2>Security explanation</h2>
        <p>AI and bot output is input for review, not authority. Quote approval, final order creation, inventory updates, price updates, and customer mutations remain outside this demo UI.</p>
      </section>
    </DashboardShell>
  );
}
