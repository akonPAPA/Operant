import { DashboardShell } from "@/components/dashboard-shell";
import { Timeline } from "@/components/timeline";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Exception Case Detail">
      <section className="panel">
        <h2>Case {id}</h2>
        <p>Review source, customer match, validation issues, suggested fixes, substitute candidates, approval requirements, notes, and operator actions.</p>
        <p className="risk-note">Workspace actions update OrderPilot workflow state only. They do not mutate product, customer, inventory, pricing, ERP, accounting, or warehouse systems.</p>
      </section>
      <div className="page-grid">
        <section className="panel"><h2>Actions</h2><p>Assign, resolve issue, waive issue, accept/reject suggested fix, create draft quote, or create draft order.</p></section>
        <section className="panel"><h2>Suggested fixes</h2><p>Customer/product selection, UOM normalization, substitute selection, price selection, and approval/manual-edit suggestions.</p></section>
        <section className="panel"><h2>Approvals</h2><p>Workflow-only approval requirements from validation and draft review.</p></section>
      </div>
      <section className="panel"><h2>Timeline</h2><Timeline items={[{ action: "CASE_OPENED", message: "Exception case created from validation output.", createdAt: "Pending API data" }]} /></section>
    </DashboardShell>
  );
}
