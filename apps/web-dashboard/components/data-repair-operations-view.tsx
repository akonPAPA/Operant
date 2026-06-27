import { getDataRepairOperationsView } from "@/lib/internal-support-operations-api";
import type { SupportOperationsTimelineEntry } from "@/lib/internal-support-operations-api";

// OP-CAP-56 — operations-oriented diagnostics view for ONE data-repair request (read-only).
//
// Consumes the OP-CAP-55 endpoint
//   GET /api/v1/internal/support/tenants/{tenantId}/data-repair-requests/{requestId}/operations-view
// and renders only the backend-owned safe fields: lifecycle status, the backend-built affected-target
// summary string, the safe processing-job repair result metadata, and the derived per-request lifecycle
// timeline. It surfaces NO requester/approver/executor identity, NO raw reason body, NO SQL/script, and
// NO cross-tenant data. Strictly display-only — it renders no execution / approval / repair control.

export async function DataRepairOperationsView({
  tenantId,
  requestId
}: Readonly<{ tenantId: string; requestId: string }>) {
  const { data, error } = await getDataRepairOperationsView(tenantId, requestId);

  if (!data) {
    return (
      <section className="panel">
        <h2>Data-repair operations view</h2>
        <p className="muted-copy">{error ?? "This data-repair operations view is unavailable right now. Please retry shortly."}</p>
      </section>
    );
  }

  return (
    <>
      <section className="panel">
        <div className="status-row">
          <h2>Data-repair operations view</h2>
          <span className="status-pill" data-state={data.approvalStatus}>
            {data.approvalStatus}
          </span>
          <span className="status-pill" data-state={data.executionStatus}>
            {data.executionStatus}
          </span>
        </div>
        <p className="risk-note">
          Read-only support diagnostics for a single data-repair request. External execution is{" "}
          {data.externalExecution}. No approval, execution, or data-repair mutation happens from this view.
        </p>
        <dl className="detail-list">
          <Field label="Request" value={data.requestId} />
          <Field label="Target type" value={data.targetType} />
          <Field label="Approval status" value={data.approvalStatus} />
          <Field label="Execution status" value={data.executionStatus} />
          <Field label="Executed" value={data.executed ? "Yes" : "No"} />
          <Field label="Dry-run summary" value={data.dryRunSummary} />
          <Field label="Affected target" value={data.affectedTargetSummary} />
        </dl>
      </section>

      <section className="panel">
        <h2>Processing-job repair result</h2>
        {data.processingJobId ? (
          <dl className="detail-list">
            <Field label="Processing job" value={data.processingJobId} />
            <Field label="Previous status" value={data.previousStatus} />
            <Field label="New status" value={data.newStatus} />
            <Field label="Executed at" value={formatTimestamp(data.executedAt)} />
          </dl>
        ) : (
          <p className="muted-copy">No processing-job repair result for this request.</p>
        )}
      </section>

      <section className="panel table-panel">
        <h2>Request lifecycle timeline</h2>
        {data.timeline.length === 0 ? (
          <p className="muted-copy">No lifecycle events recorded for this request yet.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Category</th>
                <th>Event</th>
                <th>Status</th>
                <th>Occurred</th>
              </tr>
            </thead>
            <tbody>
              {data.timeline.map((entry) => (
                <TimelineRow key={`${entry.eventType}-${entry.occurredAt}`} entry={entry} />
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

export function DataRepairOperationsViewSkeleton() {
  return (
    <section className="panel" aria-busy="true" aria-label="Loading data-repair operations view">
      <h2>Data-repair operations view</h2>
      <p className="muted-copy">Loading data-repair operations view…</p>
    </section>
  );
}

function TimelineRow({ entry }: Readonly<{ entry: SupportOperationsTimelineEntry }>) {
  return (
    <tr>
      <td>{entry.category}</td>
      <td>{entry.eventType}</td>
      <td>
        <span className="status-pill" data-state={entry.status}>
          {entry.status}
        </span>
      </td>
      <td>{formatTimestamp(entry.occurredAt)}</td>
    </tr>
  );
}

function Field({ label, value }: Readonly<{ label: string; value: string | null | undefined }>) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value === null || value === undefined || value === "" ? "—" : value}</dd>
    </div>
  );
}

function formatTimestamp(value: string | null | undefined): string {
  if (typeof value !== "string" || value.trim() === "") {
    return "—";
  }
  return value;
}
