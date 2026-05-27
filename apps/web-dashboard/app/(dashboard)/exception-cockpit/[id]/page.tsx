import { DashboardShell } from "@/components/dashboard-shell";
import { Timeline } from "@/components/timeline";

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Exception Case Detail">
      <section className="panel">
        <h2>Case {id}</h2>
        <p>Review extraction fields, evidence, confidence, validation issues, suggested corrective actions, substitute candidates, approval requirements, notes, and operator actions.</p>
        <p className="risk-note">Workspace actions update OrderPilot workflow state only. They do not mutate product, customer, inventory, pricing, ERP, accounting, or warehouse systems.</p>
      </section>
      <div className="page-grid">
        <section className="panel"><h2>Operator Actions</h2><p>Start review, approve for next stage, reject, request correction, escalate, add internal note, or confirm a candidate match inside review state.</p></section>
        <section className="panel"><h2>Suggested Actions</h2><p>Confirm customer match, select product candidate, normalize UOM, adjust quantity, select substitute candidate, request manager approval, reject result, or escalate.</p></section>
        <section className="panel"><h2>Approvals</h2><p>Workflow-only approval requirements from validation confidence, margin, discount, substitute risk, and policy checks.</p></section>
        <section className="panel"><h2>Evidence</h2><p>Extracted values show confidence, source snippets, page or message references, validation status, issue code, suggested action, and risk level.</p></section>
      </div>
      <section className="panel action-panel">
        <h2>Review Commands</h2>
        <div className="button-row">
          <button className="button" type="button">Start Review</button>
          <button className="button" type="button">Approve for Next Stage</button>
          <button className="button" type="button">Reject</button>
          <button className="button" type="button">Needs Correction</button>
          <button className="button" type="button">Escalate</button>
        </div>
      </section>
      <section className="panel"><h2>Timeline</h2><Timeline items={[{ action: "REVIEW_CASE_CREATED", message: "Review case created from validation output.", createdAt: "Pending API data" }]} /></section>
    </DashboardShell>
  );
}
