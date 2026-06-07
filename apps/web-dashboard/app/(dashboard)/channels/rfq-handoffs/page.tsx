import { DashboardShell } from "@/components/dashboard-shell";
import { RfqHandoffWorkspace } from "@/components/rfq-handoff-workspace";
import { listRfqHandoffs } from "@/lib/rfq-handoff-api";

// OP-CAP-06C RFQ Handoff Operator Workflow surface.
// Server loader: pre-fetches the tenant-scoped PENDING_REVIEW handoffs and passes them to the
// interactive operator workspace. Handoffs are created only by the verified channel/bot bridge —
// this surface never creates one. Operator transitions (start review / dismiss / mark converted)
// go through the permissioned backend command service; no quote/order or external write is possible.

export default async function Page() {
  const { data: handoffs, error } = await listRfqHandoffs("PENDING_REVIEW");

  return (
    <DashboardShell title="RFQ Handoffs">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>RFQ handoff operator review</h2>
          <p>
            RFQ handoffs are reviewable draft requests created from verified Telegram/channel messages
            that look like an RFQ. Operators take a handoff into review, inspect the request and source
            context, dismiss invalid requests with a reason, or mark a handoff converted once review is
            complete and it is ready for a later, separately-gated quote workflow.
          </p>
          <div className="tag-row">
            <span className="status-pill ok">Created only from the verified channel/bot bridge</span>
            <span className="status-pill ok">Tenant-scoped and audited transitions</span>
            <span className="status-pill ok">No quote/order, inventory, price, or ERP write</span>
          </div>
          <p className="risk-note">
            A handoff is never a quote or order. Marking it converted does not create a quote/order,
            approve anything, or trigger an external/ERP write — it is a safe internal placeholder that
            signals the request is ready for a separately-gated workflow.
          </p>
        </section>

        <RfqHandoffWorkspace initialHandoffs={handoffs} initialError={error} />
      </div>
    </DashboardShell>
  );
}
