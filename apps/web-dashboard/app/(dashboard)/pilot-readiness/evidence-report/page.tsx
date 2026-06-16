import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { getPilotEvidenceReport } from "@/lib/pilot-metrics-api";

function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

export default async function Page() {
  const { data: report, error } = await getPilotEvidenceReport();
  const hasData = report.totalShadowRuns > 0;

  return (
    <DashboardShell title="Pilot Evidence Report">
      <section className="panel report-print-area">
        <h2>Pilot Shadow-Mode Evidence Report</h2>
        <p>Design-partner / investor-ready summary of tenant-scoped pilot shadow-mode readiness.</p>
        <p className="risk-note">Shadow-mode metrics are advisory and do not execute external writes. This report is evidence of shadow-mode and pilot readiness, not a guarantee of production ROI.</p>
        {report.reportGeneratedAt ? <p className="muted">Generated {new Date(report.reportGeneratedAt).toLocaleString()}</p> : null}
        <p className="no-print"><Link className="button" href="/pilot-readiness/demo-scenarios">View demo scenarios</Link></p>
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      {!hasData ? (
        <section className="panel report-print-area">
          <h2>No pilot evidence yet</h2>
          <p>No shadow runs have been recorded for this tenant. ROI, cycle-time, and exception evidence will appear here once shadow-mode predictions and human corrections are captured.</p>
        </section>
      ) : null}

      <div className="page-grid report-print-area">
        <section className="panel"><h2>Reviewed items</h2><p>{report.totalShadowRuns} shadow runs · {report.totalHumanCorrections} corrections</p></section>
        <section className="panel"><h2>Correction rate</h2><p>{formatPercent(report.humanCorrectionRate)}</p></section>
        <section className="panel"><h2>ROI: estimated savings</h2><p>{report.estimatedMinutesSaved} min (~{report.estimatedCostSaved} {report.currency})</p></section>
        <section className="panel"><h2>Cycle time</h2><p>{report.averageManualBaselineMinutes} min manual → {report.averageAssistedProcessingMinutes} min assisted</p></section>
        <section className="panel"><h2>Automation candidates</h2><p>{report.automationCandidateCount} runs</p></section>
        <section className="panel"><h2>Review workload</h2><p>{report.reviewRequiredCount} runs require review</p></section>
      </div>

      <section className="panel table-panel report-print-area">
        <h2>Exception category breakdown</h2>
        <table className="data-table">
          <thead><tr><th>Exception category</th><th>Count</th><th>Percentage</th></tr></thead>
          <tbody>
            {report.exceptionBreakdown.map((row) => (
              <tr key={row.category}><td>{row.category}</td><td>{row.count}</td><td>{formatPercent(row.percentage)}</td></tr>
            ))}
            {report.exceptionBreakdown.length === 0 ? <tr><td colSpan={3}>No categorized exceptions for this tenant yet.</td></tr> : null}
          </tbody>
        </table>
      </section>

      <section className="panel table-panel report-print-area">
        <h2>Readiness signals</h2>
        <table className="data-table">
          <thead><tr><th>Signal</th><th>Value</th><th>Assessment</th></tr></thead>
          <tbody>
            {report.readinessSignals.map((signal) => (
              <tr key={signal.label}><td>{signal.label}</td><td>{signal.value}</td><td><span className="status-pill">{signal.assessment}</span></td></tr>
            ))}
            {report.readinessSignals.length === 0 ? <tr><td colSpan={3}>No readiness signals available.</td></tr> : null}
          </tbody>
        </table>
      </section>

      <section className="panel report-print-area">
        <h2>Safety &amp; limitations</h2>
        <p className="risk-note">{report.safetyStatement || "Shadow-mode results are advisory and never auto-approve quotes/orders or trigger ERP/1C/connector writes."}</p>
        <ul>
          {report.limitations.map((item) => (<li key={item}>{item}</li>))}
          {report.limitations.length === 0 ? <li>Raw prediction and correction payloads are intentionally excluded from this report.</li> : null}
        </ul>
        <p className="muted no-print">Use your browser&apos;s Print command to export this report as PDF.</p>
      </section>
    </DashboardShell>
  );
}
