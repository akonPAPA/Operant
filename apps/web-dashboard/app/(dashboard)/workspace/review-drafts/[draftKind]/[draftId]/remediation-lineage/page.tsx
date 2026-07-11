import Link from "next/link";

import { DashboardShell } from "@/components/dashboard-shell";
import {
  getReviewDraftRemediationLineage,
  type LineageTimelineEntry,
  type ValidationReviewDraftRemediationLineageAction,
  type ValidationReviewDraftRemediationLineageLine
} from "@/lib/server/validation-review-draft-queue-api.server";

// OP-CAP-15H — read-only remediation lineage DETAIL for one review-origin draft. Makes the OP-CAP-15G
// queue summary explainable: per draft line, the structured operator-action lineage (corrections, issue
// resolutions, approvals) plus run-scoped actions that could not be attached to a draft line. Server-
// rendered (matches the queue convention). Read-only: links out to the draft workspace and source review.
// No mutation, no final approval, no ERP/1C/connector/external action. Deterministic backend text only.

// Compact, deterministic per-line action labels. Counts/ids only — no operator-note content, no AI prose.
function actionLabels(actions: ValidationReviewDraftRemediationLineageAction[]): string {
  if (actions.length === 0) return "—";
  return actions
    .map((a) => (a.status ? `${a.actionType} (${a.status})` : a.actionType))
    .join(" · ");
}

function lineSku(line: ValidationReviewDraftRemediationLineageLine): string {
  return line.sku ?? line.description ?? "—";
}

export default async function Page({ params }: Readonly<{ params: Promise<{ draftKind: string; draftId: string }> }>) {
  const { draftKind, draftId } = await params;
  const { data, error } = await getReviewDraftRemediationLineage(draftKind, draftId);

  return (
    <DashboardShell title="Draft Remediation Lineage">
      <section className="panel">
        <h2>Draft Remediation Lineage</h2>
        <p>The structured remediation behind this review-origin draft: which corrections, issue resolutions and approvals were recorded before the draft was prepared. Read-only.</p>
        <p className="risk-note">Read-only explainability — external execution is DISABLED. No final approval, ERP/1C/connector write, or master-data mutation happens here.</p>
        <p><Link className="table-link-button" href="/workspace/review-drafts">← Back to review-origin drafts</Link></p>
      </section>

      {error ? <section className="panel"><p className="form-message error">{error}</p></section> : null}

      {data ? (
        <>
          <section className="panel">
            <h3>Summary</h3>
            {data.available ? (
              <ul className="summary-list">
                <li>Draft kind: <strong>{data.draftKind}</strong></li>
                <li>Draft lines: <strong>{data.draftLineCount}</strong> (traceable to a source line: {data.traceableDraftLineCount})</li>
                <li>Remediated before draft: <strong>{data.remediatedDraftLineCount}/{data.draftLineCount}</strong></li>
                <li>Correction actions: <strong>{data.correctionActionCount}</strong></li>
                <li>Issue-resolution actions: <strong>{data.issueResolutionActionCount}</strong></li>
                <li>Approval actions: <strong>{data.approvalActionCount}</strong></li>
                <li>External execution: <strong>{data.externalExecution}</strong></li>
              </ul>
            ) : (
              <p className="muted-copy">Remediation lineage is unavailable for this draft (it did not originate from a validation review).</p>
            )}
            <p>
              <Link className="table-link-button" href={data.workspacePath}>Open draft</Link>
              {data.reviewPath ? <> · <Link className="table-link-button" href={data.reviewPath}>Source review</Link></> : null}
            </p>
          </section>

          {data.limitations.length > 0 ? (
            <section className="panel">
              <h3>Limitations</h3>
              <ul className="limitation-list">
                {data.limitations.map((token) => (
                  <li key={token}><code>{token}</code></li>
                ))}
              </ul>
            </section>
          ) : null}

          {data.available ? (
            <section className="panel table-panel">
              <h3>Per-line lineage</h3>
              <table className="data-table">
                <thead>
                  <tr><th>Line</th><th>SKU / item</th><th>Qty</th><th>UOM</th><th>Source line</th><th>Corrections</th><th>Issue resolutions</th><th>Approvals</th><th>Limitations</th></tr>
                </thead>
                <tbody>
                  {data.lines.map((line) => (
                    <tr key={line.draftLineId}>
                      <td>{line.lineNumber}</td>
                      <td>{lineSku(line)}</td>
                      <td>{line.quantity ?? "—"}</td>
                      <td>{line.uom ?? "—"}</td>
                      <td>{line.sourceLineAvailable ? "Traceable" : "Missing"}</td>
                      <td>{actionLabels(line.correctionActions)}</td>
                      <td>{actionLabels(line.issueResolutionActions)}</td>
                      <td>{actionLabels(line.approvalActions)}</td>
                      <td>{line.limitations.length > 0 ? line.limitations.map((t) => <code key={t}>{t}</code>) : "—"}</td>
                    </tr>
                  ))}
                  {data.lines.length === 0 ? <tr><td colSpan={9}>This draft has no lines.</td></tr> : null}
                </tbody>
              </table>
            </section>
          ) : null}

          {data.available ? (
            <section className="panel table-panel">
              <h3>Remediation timeline</h3>
              <p className="muted-copy">Structured operator actions per drafted line, ordered by time. Deterministic action records only — no operator-note content.</p>
              {data.lines.some((line) => line.timeline.length > 0) ? (
                <table className="data-table">
                  <thead>
                    <tr><th>Line</th><th>When</th><th>Category</th><th>Action</th><th>Status</th><th>Summary</th></tr>
                  </thead>
                  <tbody>
                    {data.lines.flatMap((line) =>
                      line.timeline.map((entry: LineageTimelineEntry) => (
                        <tr key={entry.actionId}>
                          <td>{line.lineNumber}</td>
                          <td>{new Date(entry.createdAt).toLocaleString()}</td>
                          <td><span className="status-pill">{entry.category}</span></td>
                          <td>{entry.actionType}</td>
                          <td>{entry.status ?? "—"}</td>
                          <td>{entry.summary ?? "—"}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              ) : (
                <p className="muted-copy">No remediation actions were recorded for this draft.</p>
              )}
            </section>
          ) : null}

          {data.unattachedActions.length > 0 ? (
            <section className="panel table-panel">
              <h3>Unattached actions</h3>
              <p className="muted-copy">Structured remediation actions recorded for this review that could not be attached to a specific draft line.</p>
              <table className="data-table">
                <thead>
                  <tr><th>Category</th><th>Action</th><th>Limitation</th></tr>
                </thead>
                <tbody>
                  {data.unattachedActions.map((action) => (
                    <tr key={action.operatorActionId}>
                      <td><span className="status-pill">{action.category}</span></td>
                      <td>{action.actionType}</td>
                      <td><code>{action.limitation}</code></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ) : null}
        </>
      ) : null}
    </DashboardShell>
  );
}
