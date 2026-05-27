import { DashboardShell } from "@/components/dashboard-shell";
import { QuoteWorkspace } from "@/components/quote-workspace";

export default function Page() {
  return (
    <DashboardShell title="Draft Quote Workspace">
      <section className="panel">
        <h2>Quote Transaction Core</h2>
        <p>Submit an RFQ through the backend transaction service and inspect resolution, validation, substitutions, approvals, and audit references.</p>
        <p className="risk-note">Internal draft only - does not send to ERP/customer, reserve inventory, or execute connectors.</p>
      </section>
      <QuoteWorkspace />
    </DashboardShell>
  );
}
