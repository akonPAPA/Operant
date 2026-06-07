import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { getPilotDemoScenarios } from "@/lib/pilot-metrics-api";

export default async function Page() {
  const { data: pack, error } = await getPilotDemoScenarios();
  const hasScenarios = pack.scenarios.length > 0;

  return (
    <DashboardShell title="Pilot Demo Scenarios">
      <section className="panel report-print-area">
        <h2>Pilot Demo Scenario Pack</h2>
        <p>Honest, read-only demo-readiness for the core investor/design-partner scenarios. Each scenario shows what exists, what is missing, and the safety boundaries.</p>
        <p className="risk-note">Read-only readiness view. No business actions are executed; AI output is advisory; no ERP/1C/connector writes occur. Readiness never claims production completeness.</p>
        <p>
          <Link className="button" href="/pilot-readiness">Back to Pilot Readiness</Link>{" "}
          <Link className="button" href="/pilot-readiness/evidence-report">Open evidence report</Link>
        </p>
        {pack.reportGeneratedAt ? <p className="muted">Generated {new Date(pack.reportGeneratedAt).toLocaleString()} · evidence present: {String(pack.tenantHasPilotEvidence)}</p> : null}
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      {!hasScenarios ? (
        <section className="panel report-print-area"><h2>No scenarios available</h2><p>The scenario pack could not be loaded for this tenant.</p></section>
      ) : null}

      {pack.scenarios.map((scenario) => (
        <section className="panel report-print-area" key={scenario.code}>
          <h2>{scenario.title} <span className="status-pill">{scenario.readiness}</span></h2>
          <p className="muted">{scenario.code} · {scenario.channelSourceType} · {scenario.primaryActorRole} · readiness {scenario.readinessScore}%</p>
          <p>{scenario.businessObjective}</p>

          <h3>Required capabilities</h3>
          <table className="data-table">
            <thead><tr><th>Capability</th><th>Available</th><th>Note</th></tr></thead>
            <tbody>
              {scenario.requiredCapabilities.map((cap) => (
                <tr key={cap.name}><td>{cap.name}</td><td>{cap.available ? "Yes" : "No"}</td><td>{cap.note}</td></tr>
              ))}
            </tbody>
          </table>

          <h3>Evidence signals</h3>
          <ul>
            {scenario.evidenceSignals.map((sig) => (<li key={sig.label}>{sig.label}: {sig.value}</li>))}
            {scenario.evidenceSignals.length === 0 ? <li>No evidence signals yet.</li> : null}
          </ul>

          <h3>Missing capabilities / gaps</h3>
          <ul>
            {scenario.missingCapabilities.map((gap) => (<li key={gap}>{gap}</li>))}
            {scenario.missingCapabilities.length === 0 ? <li>No outstanding gaps recorded.</li> : null}
          </ul>

          <h3>Safety boundaries</h3>
          <ul>
            {scenario.safetyBoundaries.map((b) => (<li key={b.statement}>{b.statement}</li>))}
          </ul>

          <h3>Operator talking points</h3>
          <ul>
            {scenario.operatorTalkingPoints.map((point) => (<li key={point}>{point}</li>))}
          </ul>

          <p className="muted">
            Suggested demo route: <Link href={scenario.suggestedDemoRoute}>{scenario.suggestedDemoRoute}</Link>
          </p>
        </section>
      ))}

      <section className="panel report-print-area">
        <h2>Safety &amp; limitations</h2>
        <p className="risk-note">{pack.safetyStatement || "Shadow-mode results are advisory and never auto-approve quotes/orders or trigger ERP/1C/connector writes."}</p>
        <ul>
          {pack.packLimitations.map((item) => (<li key={item}>{item}</li>))}
        </ul>
      </section>
    </DashboardShell>
  );
}
