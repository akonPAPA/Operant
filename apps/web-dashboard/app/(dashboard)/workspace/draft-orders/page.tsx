import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import { getDraftOrderReviewQueue } from "@/lib/draft-review-api";

const STATUS_OPTIONS = ["", "DRAFT", "NEEDS_REVIEW", "WAITING_APPROVAL", "APPROVED_INTERNAL", "REJECTED", "CANCELLED"];

export default async function Page({ searchParams }: Readonly<{ searchParams: Promise<{ status?: string }> }>) {
  const { status } = await searchParams;
  const { data: summaries, error } = await getDraftOrderReviewQueue({ status });

  return (
    <DashboardShell title="Draft Order Review Queue">
      <section className="panel">
        <h2>Draft Order Review Queue</h2>
        <p>Internal draft orders prepared from approved validation review cases. Open a row to review and correct lines.</p>
        <p className="risk-note">Internal draft review only — external execution is DISABLED. No final order approval, ERP/1C/accounting/connector write, inventory reservation, or master-data mutation happens here.</p>
      </section>

      <section className="panel">
        <form className="control-grid" method="get">
          <label className="field-label" htmlFor="status">Status</label>
          <select id="status" name="status" className="form-input" defaultValue={status ?? ""}>
            {STATUS_OPTIONS.map((value) => (
              <option key={value || "ALL"} value={value}>{value === "" ? "All statuses" : value}</option>
            ))}
          </select>
          <button className="button" type="submit">Filter</button>
        </form>
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr><th>Draft</th><th>Status</th><th>Customer account</th><th>Lines</th><th>Total</th><th>Source review case</th><th>External</th><th>Created</th><th>Open</th></tr>
          </thead>
          <tbody>
            {summaries.map((summary) => (
              <tr key={summary.draftId}>
                <td>{summary.draftId}</td>
                <td><span className="status-pill">{summary.status}</span></td>
                <td>{summary.customerAccountId ?? "—"}</td>
                <td>{summary.lineCount}</td>
                <td>{summary.totalAmount ?? "—"}{summary.currency ? ` ${summary.currency}` : ""}</td>
                <td>{summary.sourceReviewCaseId ?? "—"}</td>
                <td>{summary.externalExecution}</td>
                <td>{new Date(summary.createdAt).toLocaleString()}</td>
                <td><Link className="button table-link-button" href={`/workspace/draft-orders/${summary.draftId}`}>Review lines</Link></td>
              </tr>
            ))}
            {summaries.length === 0 ? <tr><td colSpan={9}>No draft orders match the current filter for this tenant.</td></tr> : null}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
