import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { getPilotDemoScenarios } from "@/lib/server/pilot-metrics-api.server";

// OP-CAP-11I scripted demo dataset summary (static, display-only). Mirrors
// apps/core-api/src/test/resources/demo/core-v1-demo/scripted-scenarios-demo.json.
const SCRIPTED_DEMO_DATASET = [
  { code: "TELEGRAM_RFQ_SUBSTITUTION", inputSample: "Telegram RFQ: 2 EA brake pads for Toyota Camry 2018 (original out of stock, accepted substitute exists)." },
  { code: "PDF_PO_EXCEPTION", inputSample: "Purchase order with an ambiguous SKU line and an unsupported unit-of-measure line." },
  { code: "DISCOUNT_MARGIN_GUARDRAIL", inputSample: "Discount request that drops line margin below the threshold and requires manager approval." },
  { code: "INVENTORY_MISMATCH", inputSample: "Expected stock 116 vs actual count 100 (mismatch -16) opening a reconciliation discrepancy." },
  { code: "BAD_AI_OUTPUT_REJECTED", inputSample: "Prompt-injection-like text and malformed model output, kept as untrusted data and rejected." },
] as const;

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

      <section className="panel">
        <h2>Scripted demo dataset</h2>
        <p>These scenarios are backed by a deterministic, fake demo dataset (OP-CAP-11I) so the pack and evidence report look realistic during investor / design-partner demos without using production data.</p>
        <p className="risk-note">Demo data only. The seed is local/demo/test scoped, uses no real customers, secrets, or credentials, makes no external/AI/ERP calls, and is never loaded in production.</p>
        <table className="data-table">
          <thead><tr><th>Scenario</th><th>Scripted input sample</th></tr></thead>
          <tbody>
            {SCRIPTED_DEMO_DATASET.map((row) => (
              <tr key={row.code}><td>{row.code}</td><td>{row.inputSample}</td></tr>
            ))}
          </tbody>
        </table>
        <p className="muted">
          Fixtures: <code>apps/core-api/src/test/resources/demo/core-v1-demo/</code> · dataset doc:{" "}
          <code>docs/pilot/PILOT_SCRIPTED_DEMO_DATASET.md</code>
        </p>
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
