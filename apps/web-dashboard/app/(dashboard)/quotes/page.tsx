import { DashboardShell } from "@/components/dashboard-shell";
import { QuoteWorkspace } from "@/components/quote-workspace";
import { UnavailableState } from "@/components/page-states";
import { loadUiCapabilityProjection } from "@/lib/server/load-ui-capability-projection.server";

/**
 * Draft quote workspace: read offer requires VIEW_QUOTES; mutation controls require QUOTE_ACTION.
 * Capability projection is offer filtering only — BFF/Core authorize every mutation independently.
 */
export default async function Page() {
  const projection = await loadUiCapabilityProjection();
  const mayViewQuotes =
    projection.status === "ALLOWED" && projection.capabilities.includes("VIEW_QUOTES");
  const mayPerformQuoteAction =
    projection.status === "ALLOWED" && projection.capabilities.includes("QUOTE_ACTION");

  if (!mayViewQuotes) {
    return (
      <DashboardShell title="Draft Quote Workspace">
        <UnavailableState
          title="Quote workspace unavailable"
          description="This workspace requires a projected quote read capability for your session."
          reason={
            projection.status === "UNAVAILABLE"
              ? "Capability projection is temporarily unavailable. Protected quote data is withheld."
              : "Your session does not include quote read access."
          }
        />
      </DashboardShell>
    );
  }

  return (
    <DashboardShell title="Draft Quote Workspace">
      <section className="panel">
        <h2>Quote Transaction Core</h2>
        <p>
          Submit an RFQ through the backend transaction service and inspect resolution, validation,
          substitutions, approvals, and audit references.
        </p>
        <p className="risk-note">
          Internal draft only - does not send to ERP/customer, reserve inventory, or execute connectors.
        </p>
      </section>
      <QuoteWorkspace canPerformQuoteAction={mayPerformQuoteAction} />
    </DashboardShell>
  );
}
