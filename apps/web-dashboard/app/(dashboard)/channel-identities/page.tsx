import { ChannelIdentityWorkspace } from "@/components/channel-identity-workspace";
import { DashboardShell } from "@/components/dashboard-shell";
import { listChannelIdentities } from "@/lib/channel-identity-api";

// OP-CAP-06E Channel Identity operator review and control surface.
// Server loader: pre-fetches the tenant-scoped identity list and passes it to the interactive
// workspace. Mutations require CHANNEL_IDENTITY_ACTION — never BOT_ACTION or open access.
// No auto-linking, no bot or AI direct mutation path is offered or possible from this surface.

export default async function Page() {
  const { data: identities, error } = await listChannelIdentities();

  return (
    <DashboardShell title="Channel Identities">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>Channel identity review and control</h2>
          <p>
            Channel identities map verified inbound sender IDs (provider-derived, never customer-claimed) to
            tenant-owned customer accounts and contacts. Operators confirm, block, or flag identities for review.
            Bot runtime policy uses these decisions to allow, route, or reject inbound flows.
          </p>
          <div className="tag-row">
            <span className="status-pill ok">Identity keyed by provider-derived sender ID only</span>
            <span className="status-pill ok">No auto-linking from inbound messages</span>
            <span className="status-pill ok">Mutations require CHANNEL_IDENTITY_ACTION</span>
          </div>
          <p className="risk-note">
            Linking an identity to a customer does not approve quotes, orders, or discounts.
            It does not bypass bot configuration, deterministic validation, or operator review requirements.
            Blocked senders are denied bot business responses by the runtime identity gate.
          </p>
        </section>

        <ChannelIdentityWorkspace
          initialIdentities={identities}
          initialError={error}
        />
      </div>
    </DashboardShell>
  );
}
