import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { listValidationReviewCases } from "@/lib/server/validation-review-api.server";

export default async function Page() {
  const { data: cases, error } = await listValidationReviewCases();

  return (
    <DashboardShell title="Validation Review">
      <section className="panel">
        <h2>Review Queue</h2>
        <p>Operator workspace for validation outcomes approved through the Phase 5B review bridge.</p>
        <p className="risk-note">This queue prepares internal draft commands only. It does not approve quotes/orders, reserve inventory, or write to ERP.</p>
      </section>
      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}
      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr><th>Review case</th><th>Extraction</th><th>Status</th><th>Severity</th><th>Issues</th><th>Approvals</th><th>Created</th><th>Open</th></tr>
          </thead>
          <tbody>
            {cases.map((reviewCase) => (
              <tr key={reviewCase.id}>
                <td>{reviewCase.caseNumber}<br /><span className="muted-copy">{reviewCase.id}</span></td>
                <td>{reviewCase.extractionResultId}</td>
                <td><span className={`status-pill ${reviewCase.status.includes("REJECTED") || reviewCase.status.includes("BLOCKED") ? "warning" : ""}`}>{reviewCase.status}</span></td>
                <td>{reviewCase.severity}</td>
                <td>{issueCount(reviewCase.summary)}</td>
                <td>{approvalState(reviewCase.status)}</td>
                <td>{new Date(reviewCase.createdAt).toLocaleString()}</td>
                <td><Link className="button table-link-button" href={`/validation-review/${reviewCase.id}`}>Open</Link></td>
              </tr>
            ))}
            {cases.length === 0 ? <tr><td colSpan={8}>No validation review cases are available for the current tenant.</td></tr> : null}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}

function issueCount(summary?: string) {
  const match = summary?.match(/(\d+)/);
  return match?.[1] ?? "0";
}

function approvalState(status: string) {
  if (status.includes("APPROVED")) return "Approved";
  if (status.includes("WAITING")) return "Open";
  return "See detail";
}
