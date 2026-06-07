import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { getPilotExceptionBreakdown, getPilotMetrics } from "@/lib/pilot-metrics-api";

function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function topException(counts: Record<string, number>): string {
  const entries = Object.entries(counts);
  if (entries.length === 0) {
    return "—";
  }
  const [category, count] = entries.reduce((best, current) => (current[1] > best[1] ? current : best));
  return `${category} (${count})`;
}

export default async function Page() {
  const [{ data: metrics, error: metricsError }, { data: breakdown, error: breakdownError }] = await Promise.all([
    getPilotMetrics(),
    getPilotExceptionBreakdown()
  ]);
  const error = metricsError ?? breakdownError;

  return (
    <DashboardShell title="Pilot Readiness">
      <section className="panel">
        <h2>Shadow-Mode ROI Readiness</h2>
        <p>Tenant-scoped pilot evidence from advisory shadow-mode predictions and human corrections.</p>
        <p className="risk-note">Shadow-mode metrics are advisory and do not execute external writes. Predictions never approve quotes/orders; humans remain authoritative.</p>
        <p>
          <Link className="button" href="/pilot-readiness/evidence-report">Open evidence report</Link>{" "}
          <Link className="button" href="/pilot-readiness/demo-scenarios">View demo scenarios</Link>
        </p>
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      <div className="page-grid">
        <section className="panel"><h2>Reviewed items</h2><p>{metrics.reviewedShadowRuns} / {metrics.totalShadowRuns} shadow runs</p></section>
        <section className="panel"><h2>Correction rate</h2><p>{formatPercent(metrics.humanCorrectionRate)}</p></section>
        <section className="panel"><h2>Estimated minutes saved</h2><p>{metrics.estimatedMinutesSaved} min (~{metrics.estimatedCostSaved} {metrics.costCurrency})</p></section>
        <section className="panel"><h2>Review required</h2><p>{metrics.reviewRequiredCount} runs</p></section>
        <section className="panel"><h2>Top exception category</h2><p>{topException(metrics.exceptionCategoryCounts)}</p></section>
        <section className="panel"><h2>Automation candidates</h2><p>{metrics.automationCandidateCount} runs</p></section>
      </div>

      <section className="panel table-panel">
        <h2>Exception category breakdown</h2>
        <table className="data-table">
          <thead>
            <tr><th>Exception category</th><th>Count</th><th>Percentage</th></tr>
          </thead>
          <tbody>
            {breakdown.categories.map((row) => (
              <tr key={row.category}>
                <td>{row.category}</td>
                <td>{row.count}</td>
                <td>{formatPercent(row.percentage)}</td>
              </tr>
            ))}
            {breakdown.categories.length === 0 ? <tr><td colSpan={3}>No categorized exceptions for this tenant yet.</td></tr> : null}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Safety Boundary</h2>
        <p className="risk-note">Pilot evidence is advisory only. AI suggests, rules validate, humans approve, the backend writes, audit records. No ERP/1C/connector write, no quote/order approval, and no master-data mutation happens here.</p>
      </section>
    </DashboardShell>
  );
}
