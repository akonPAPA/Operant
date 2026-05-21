import { DashboardShell } from "@/components/dashboard-shell";

const sections = [
  ["Customer match", "Matched account, match type, confidence, and ambiguous candidates."],
  ["Product matches", "SKU, alias, OEM reference, and description-search outcomes by extracted line item."],
  ["UOM normalization", "Common unit variants normalized into tenant-safe units such as EA, BOX, SET, and KIT."],
  ["Inventory checks", "Latest inventory snapshots compared against requested quantities."],
  ["Price checks", "Applicable price rule selection with customer, segment, quantity, date, and priority handling."],
  ["Discount checks", "Requested discount compared against deterministic tenant discount rules."],
  ["Margin checks", "Gross margin guardrails based on unit price and product cost."],
  ["Substitute candidates", "Deterministic substitute candidates ranked by risk, stock preference, customer preference, and margin signal."],
  ["Validation issues", "Open, resolved, and waived deterministic validation findings."],
  ["Approval requirements", "Workflow-only approvals for risky validation outcomes."]
];

export default async function Page({ params }: Readonly<{ params: Promise<{ id: string }> }>) {
  const { id } = await params;
  return (
    <DashboardShell title="Validation Detail">
      <section className="panel">
        <h2>Run Summary</h2>
        <p>Run {id} shows the deterministic validation result prepared for operator review and the Stage 6 quote/order workspace.</p>
        <p className="risk-note">Approving a Stage 5 requirement only approves validation workflow output. It does not create a quote/order or write to ERP.</p>
      </section>
      <section className="panel action-panel">
        <h2>Workspace Actions</h2>
        <div className="button-row">
          <button className="button" type="button">Create Exception Case</button>
          <button className="button" type="button">Generate Suggested Fixes</button>
          <button className="button" type="button">Create Draft Quote</button>
          <button className="button" type="button">Create Draft Order</button>
        </div>
        <p className="risk-note">These actions create internal workflow records only. They do not send customer messages, create external orders, or update ERP/warehouse systems.</p>
      </section>
      <div className="page-grid">
        {sections.map(([title, body]) => (
          <section className="panel" key={title}><h2>{title}</h2><p>{body}</p></section>
        ))}
      </div>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Action</th><th>Scope</th><th>Status</th><th>Safety note</th></tr></thead>
          <tbody>
            <tr><td>Resolve issue</td><td>Validation issue</td><td>Placeholder</td><td>Workflow-only issue state.</td></tr>
            <tr><td>Waive issue</td><td>Validation issue</td><td>Placeholder</td><td>Auditable review action.</td></tr>
            <tr><td>Approve requirement</td><td>Approval requirement</td><td>Placeholder</td><td>No quote/order or ERP update.</td></tr>
            <tr><td>Reject requirement</td><td>Approval requirement</td><td>Placeholder</td><td>No external write.</td></tr>
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
