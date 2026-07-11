import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  getReviewDraftQueue,
  getReviewDraftRecentRemediationRollup,
  remediationLineagePath,
  type ValidationReviewDraftRemediationSummary
} from "@/lib/server/validation-review-draft-queue-api.server";

// OP-CAP-15C/15G — lite, read-only queue of internal drafts created from validation reviews.
// Server-rendered (matches the existing workspace review-queue convention). Read-only: links out to the
// draft workspace and back to the source validation review. No final approval / ERP / external action.
// OP-CAP-15G adds a compact remediation-lineage summary derived from structured records only (plain text).
const TYPE_OPTIONS = ["", "QUOTE", "ORDER"];

// Compact, manager-readable remediation summary. Plain text only — no raw ids, no operator-note content.
function remediationActionsText(summary: ValidationReviewDraftRemediationSummary): string {
  const parts: string[] = [];
  if (summary.correctionActionCount > 0) parts.push(`${summary.correctionActionCount} correction`);
  if (summary.issueResolutionActionCount > 0) parts.push(`${summary.issueResolutionActionCount} issue resolution`);
  if (summary.approvalActionCount > 0) parts.push(`${summary.approvalActionCount} approval`);
  return parts.length > 0 ? parts.join(" · ") : "no linked actions";
}

export default async function Page({ searchParams }: Readonly<{ searchParams: Promise<{ draftType?: string; status?: string }> }>) {
  const { draftType, status } = await searchParams;
  // Fetch the queue and the recent remediation tile in parallel. A tile failure must never break the queue.
  const [{ data, error }, rollupResult] = await Promise.all([
    getReviewDraftQueue({ draftType, status }),
    getReviewDraftRecentRemediationRollup()
  ]);
  const items = data?.items ?? [];
  const rollup = rollupResult.data;
  const rollupError = rollupResult.error;

  return (
    <DashboardShell title="Review-Origin Drafts">
      <section className="panel">
        <h2>Review-Origin Drafts</h2>
        <p>Internal drafts (quotes and orders) created from validation reviews. Open a row to review the draft, or jump back to the source validation review.</p>
        <p className="risk-note">Internal draft review only — external execution is DISABLED. No final approval, ERP/1C/connector write, or master-data mutation happens here.</p>
      </section>

      <section className="panel" aria-label="Recent remediation">
        <h3>Recent remediation</h3>
        {rollupError ? (
          <p className="form-message error">Recent remediation summary is unavailable: {rollupError}</p>
        ) : rollup && rollup.inspectedDraftCount > 0 ? (
          <>
            <ul className="summary-list">
              <li>Inspected drafts: <strong>{rollup.inspectedDraftCount}</strong> (review-origin: {rollup.reviewOriginDraftCount})</li>
              <li>Lineage available / unavailable: <strong>{rollup.lineageAvailableDraftCount}</strong> / {rollup.lineageUnavailableDraftCount}</li>
              <li>Remediated / traceable lines: <strong>{rollup.remediatedDraftLineCount}</strong> / {rollup.traceableDraftLineCount}</li>
              <li>Structured actions: <strong>{rollup.remediationActionCount}</strong> ({rollup.correctionActionCount} correction · {rollup.issueResolutionActionCount} issue · {rollup.approvalActionCount} approval)</li>
              <li>Latest remediation action: <strong>{rollup.latestRemediationActionAt ? new Date(rollup.latestRemediationActionAt).toLocaleString() : "—"}</strong></li>
            </ul>
            {rollup.limitationCodes.length > 0 ? (
              <p className="muted-copy">Limitations: {rollup.limitationCodes.map((code) => <code key={code}> {code}</code>)}</p>
            ) : null}
          </>
        ) : (
          <p className="muted-copy">No recent review-draft remediation activity for this tenant yet.</p>
        )}
      </section>

      <section className="panel">
        <form className="control-grid" method="get">
          <label className="field-label" htmlFor="draftType">Draft type</label>
          <select id="draftType" name="draftType" className="form-input" defaultValue={draftType ?? ""}>
            {TYPE_OPTIONS.map((value) => (
              <option key={value || "ALL"} value={value}>{value === "" ? "All types" : value}</option>
            ))}
          </select>
          <label className="field-label" htmlFor="status">Status</label>
          <input id="status" name="status" className="form-input" type="text" defaultValue={status ?? ""} placeholder="e.g. DRAFT" />
          <button className="button" type="submit">Filter</button>
        </form>
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      <section className="panel table-panel">
        <table className="data-table">
          <thead>
            <tr><th>Draft</th><th>Type</th><th>Status</th><th>Customer</th><th>Lines</th><th>Remediation</th><th>Rollup</th><th>Lineage</th><th>Note</th><th>External</th><th>Created</th><th>Open draft</th><th>Source review</th></tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.draftId}>
                <td>{item.draftId.slice(0, 8)}…</td>
                <td><span className="status-pill">{item.draftType}</span></td>
                <td><span className="status-pill">{item.status}</span></td>
                <td>{item.customerDisplay ?? "—"}</td>
                <td>{item.lineCount}</td>
                <td>
                  {item.remediationSummary && item.remediationSummary.available ? (
                    <span className="remediation-summary">
                      Remediated before draft: {item.remediationSummary.remediatedDraftLineCount}/{item.remediationSummary.draftLineCount}
                      <span className="muted-copy"> · {remediationActionsText(item.remediationSummary)}</span>
                    </span>
                  ) : (
                    <span className="muted-copy">Remediation lineage unavailable</span>
                  )}
                </td>
                <td>
                  {item.remediationRollup && item.remediationRollup.remediationLineageAvailable ? (
                    <span className="remediation-rollup">
                      {item.remediationRollup.remediationActionCount} action{item.remediationRollup.remediationActionCount === 1 ? "" : "s"} · {item.remediationRollup.remediatedLineCount}/{item.remediationRollup.traceableLineCount} lines
                      {item.remediationRollup.latestRemediationActionAt ? (
                        <span className="muted-copy"> · last {new Date(item.remediationRollup.latestRemediationActionAt).toLocaleString()}</span>
                      ) : null}
                    </span>
                  ) : (
                    <span className="muted-copy">
                      No remediation rollup
                      {item.remediationRollup && item.remediationRollup.limitationCodes.length > 0
                        ? item.remediationRollup.limitationCodes.map((code) => <code key={code}> {code}</code>)
                        : null}
                    </span>
                  )}
                </td>
                <td><Link className="table-link-button" href={remediationLineagePath(item.draftType, item.draftId)}>Remediation lineage</Link></td>
                <td>{item.operatorNotePresent ? "Yes" : "—"}</td>
                <td>{item.externalExecution}</td>
                <td>{new Date(item.createdAt).toLocaleString()}</td>
                <td><Link className="button table-link-button" href={item.workspacePath}>Open draft</Link></td>
                <td>{item.reviewPath ? <Link className="table-link-button" href={item.reviewPath}>Review</Link> : "—"}</td>
              </tr>
            ))}
            {items.length === 0 ? <tr><td colSpan={13}>No drafts have been created from validation reviews for this tenant yet.</td></tr> : null}
          </tbody>
        </table>
      </section>
    </DashboardShell>
  );
}
