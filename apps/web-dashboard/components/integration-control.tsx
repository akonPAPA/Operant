import { ChangeRequestQueue } from "@/components/change-request-queue";
import { ConnectorAuditTimeline } from "@/components/connector-audit-timeline";
import { ConnectorSyncRuns } from "@/components/connector-sync-runs";
import { getStage9ChangeRequests, getStage9ConnectorAudit, getStage9ConnectorPolicy, getStage9ConnectorSyncRuns, getStage9Integrations } from "@/lib/stage9-integration-api";

export async function IntegrationControl() {
  const [integrations, changeRequests, syncRuns, policy, auditEvents] = await Promise.all([
    getStage9Integrations(),
    getStage9ChangeRequests(),
    getStage9ConnectorSyncRuns(),
    getStage9ConnectorPolicy(),
    getStage9ConnectorAudit()
  ]);
  const demoErp = integrations.find((connection) => connection.connectionKind === "DEMO_ERP_LOCAL" || connection.displayName.includes("Demo ERP"));

  return (
    <div className="demo-stack">
      <section className="panel">
        <h2>Integration Control</h2>
        <div className="kpi-grid">
          <Metric label="Mode" value="READ_ONLY default" />
          <Metric label="Execution mode" value={policy?.executionMode ?? "DEMO_ONLY"} />
          <Metric label="Credential status" value={policy?.credentialStatus ?? "CONFIGURED_PLACEHOLDER"} />
          <Metric label="External writes" value="Blocked for production" />
          <Metric label="Network calls" value="None" />
        </div>
        <div className="tag-row">
          {(policy?.capabilities ?? ["READ_CUSTOMERS", "READ_PRODUCTS", "READ_INVENTORY", "CREATE_DRAFT_QUOTE", "CREATE_DRAFT_ORDER", "FETCH_STATUS"]).map((capability) => (
            <span className="status-pill" key={capability}>{capability}</span>
          ))}
        </div>
        <p className="risk-note">Demo ERP only: Stage 9B hardens connector safety without production ERP or 1C writes, secrets, connector commands from bot handoffs, or inventory mutation.</p>
        <p className="risk-note">Production connector disabled: activation requires separate security acceptance, real secret-manager custody, idempotency review, and runbook signoff.</p>
      </section>

      <section className="panel">
        <h2>Demo ERP connection</h2>
        <dl className="metric-list">
          <div><dt>Status</dt><dd>{demoErp?.status ?? "Not created"}</dd></div>
          <div><dt>Mode</dt><dd>{demoErp?.mode ?? "READ_ONLY"}</dd></div>
          <div><dt>Connection kind</dt><dd>{demoErp?.connectionKind ?? "DEMO_ERP_LOCAL"}</dd></div>
          <div><dt>Endpoint</dt><dd>{demoErp?.endpointRef ?? "demo://local"}</dd></div>
          <div><dt>Credential</dt><dd>{policy?.maskedCredentialRef ?? "placeholder:not-configured"}</dd></div>
        </dl>
        <p className="risk-note">The Demo ERP adapter generates deterministic local external references and stores sync/audit records only.</p>
      </section>

      <ChangeRequestQueue changeRequests={changeRequests} />
      <ConnectorSyncRuns syncRuns={syncRuns} />
      <ConnectorAuditTimeline events={auditEvents} />
    </div>
  );
}

function Metric({ label, value }: Readonly<{ label: string; value: string }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
