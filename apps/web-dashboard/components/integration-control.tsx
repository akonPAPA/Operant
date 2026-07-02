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
          <Metric label="Production writes" value={policy?.productionWritesEnabled ? "Enabled" : "Disabled"} />
          <Metric label="Network calls" value={policy?.networkCallsAllowed ? "Enabled" : "Disabled"} />
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
          <div><dt>Safety policy</dt><dd>{policy?.warning ?? "Production connector execution is disabled."}</dd></div>
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
