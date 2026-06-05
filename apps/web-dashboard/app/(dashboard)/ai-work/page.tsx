import { AiWorkAssistantWorkspace } from "@/components/ai-work-assistant-workspace";
import { DashboardShell } from "@/components/dashboard-shell";
import { listRecentAiWork } from "@/lib/ai-work-api";

// OP-CAP-07A AI Agent Work Layer — AI Work Assistant operator surface.
// Server loader pre-fetches the tenant-scoped recent advisory suggestions and passes them to the
// interactive workspace. Reads require REVIEW_READ; generation/accept/reject require AI_WORK_ACTION.
// Suggestions are advisory only: nothing on this surface approves quotes, orders, discounts, or
// substitutes, and nothing here performs an external/ERP write.

export default async function Page() {
  const { data: suggestions, error } = await listRecentAiWork(50);

  return (
    <DashboardShell title="AI Work Assistant">
      <div className="demo-stack">
        <section className="panel trust-panel">
          <h2>AI-assisted operator work — advisory only</h2>
          <p>
            The AI Work Assistant helps operators understand, review, and prepare safe next steps for
            quote, order, and channel work. It can summarize an inbound request, explain validation
            issues, draft a customer reply, suggest internal next actions, and digest source context.
          </p>
          <div className="tag-row">
            <span className="status-pill ok">AI suggests · rules validate · human approves · backend writes · audit records</span>
            <span className="status-pill ok">No quote/order/discount/substitute approval</span>
            <span className="status-pill ok">No ERP or connector writes</span>
          </div>
          <p className="risk-note">
            Suggestions are stored separately from trusted business state. Accepting a suggestion only
            records that an operator accepted the advisory text or idea — it does not approve a quote,
            order, discount, or substitute, and it does not execute any external write. Customer reply
            drafts are draft only and are never sent from this surface.
          </p>
        </section>

        <AiWorkAssistantWorkspace initialSuggestions={suggestions} initialError={error} />
      </div>
    </DashboardShell>
  );
}
