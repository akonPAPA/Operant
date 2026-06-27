import Link from "next/link";

import {
  getSupportOperationsSummary,
  getSupportOperationsTimeline,
  getSupportTenantContext
} from "@/lib/internal-support-operations-api";
import type {
  SupportOperationsSummary,
  SupportOperationsTimelineEntry
} from "@/lib/internal-support-operations-api";

// OP-CAP-56/57 — internal owner-company support operations visibility surface (read-only).
//
// Consumes the OP-CAP-55 read-only endpoints for a SELECTED tenant (OP-CAP-57 replaced the demo-tenant env
// assumption — the tenant id is a navigation handle resolved from the locator, and the backend re-validates
// the support grant on every call). Renders backend-derived counts and a bounded chronological lifecycle
// timeline. Strictly display-only: it submits nothing and mutates no incident/grant/break-glass/data-repair/
// business state. It renders ONLY the operator-safe scalar fields the OP-CAP-55 contract returns — never any
// raw request body, internal actor identity, audit-row internals, storage locators, or the raw backend error
// body (the contract excludes all of those).

// OP-CAP-57 — the JIT support-grant boundary header. Confirms (read-only) that the staff actor holds an
// active diagnostics grant for the selected tenant and shows the safe grant boundary (display name, active
// scopes, expiry). A denied/no-grant/expired-grant state renders a safe message and never reveals whether
// the tenant exists or which condition failed. Returns whether operations panels should be shown.
export async function SupportTenantContextPanel({ tenantId }: Readonly<{ tenantId: string }>) {
  const { data, error } = await getSupportTenantContext(tenantId);

  if (!data) {
    return (
      <section className="panel">
        <h2>Support access</h2>
        <p className="form-message error">{error ?? "Support access could not be confirmed for this tenant."}</p>
      </section>
    );
  }

  return (
    <section className="panel">
      <div className="status-row">
        <h2>{data.displayName}</h2>
        <span className="status-pill" data-state={data.status}>
          {data.status}
        </span>
        {data.readOnly ? <span className="status-pill" data-customer="true">Read-only</span> : null}
      </div>
      <p className="risk-note">
        Active support grant for this tenant. External execution is {data.externalExecution}. No mutation,
        impersonation, repair, or break-glass action is available from this surface.
      </p>
      <dl className="detail-list">
        <div>
          <dt>Tenant handle</dt>
          <dd>{data.slug}</dd>
        </div>
        <div>
          <dt>Active support scopes</dt>
          <dd>{data.supportScopes.length > 0 ? data.supportScopes.join(", ") : "—"}</dd>
        </div>
        <div>
          <dt>Grant expires</dt>
          <dd>{formatTimestamp(data.grantExpiresAt)}</dd>
        </div>
      </dl>
      {data.canViewOperations ? null : (
        <p className="muted-copy">This grant does not permit read-only operations visibility.</p>
      )}
    </section>
  );
}

export async function SupportOperationsSummaryPanel({ tenantId }: Readonly<{ tenantId: string }>) {
  const { data, error } = await getSupportOperationsSummary(tenantId);

  if (!data) {
    return (
      <section className="panel">
        <h2>Operations summary</h2>
        <p className="muted-copy">{error ?? "Operations summary is unavailable right now. Please retry shortly."}</p>
      </section>
    );
  }

  return (
    <section className="panel">
      <h2>Operations summary</h2>
      <p className="risk-note">
        Read-only support diagnostics. External execution is {data.externalExecution}. No incident, grant,
        break-glass, data-repair, or business mutation happens from this surface.
      </p>
      <div className="kpi-grid">
        <SummaryCell label="Open incidents" value={data.openIncidents} />
        <SummaryCell label="Critical open incidents" value={data.criticalOpenIncidents} />
        <SummaryCell label="Pending break-glass requests" value={data.pendingBreakGlassRequests} />
        <SummaryCell label="Active break-glass grants" value={data.approvedActiveBreakGlassRequests} />
        <SummaryCell label="Pending support grants" value={data.pendingSupportGrants} />
        <SummaryCell label="Active support grants" value={data.activeSupportGrants} />
        <SummaryCell label="Pending data-repair approvals" value={data.pendingDataRepairApprovals} />
        <SummaryCell label="Approved data-repair requests" value={data.approvedDataRepairRequests} />
        <SummaryCell label="Executed processing-job repairs" value={data.executedProcessingJobRepairs} />
        <SummaryCell label="Rejected data-repair requests" value={data.rejectedDataRepairRequests} />
      </div>
      <dl className="detail-list">
        <div>
          <dt>Latest activity</dt>
          <dd>{formatTimestamp(data.latestActivityAt)}</dd>
        </div>
        <div>
          <dt>Generated</dt>
          <dd>{formatTimestamp(data.generatedAt)}</dd>
        </div>
      </dl>
    </section>
  );
}

export async function SupportOperationsTimelinePanel({
  tenantId,
  page,
  size
}: Readonly<{ tenantId: string; page: number; size: number }>) {
  const { data, error } = await getSupportOperationsTimeline(tenantId, { page, size });

  if (!data) {
    return (
      <section className="panel">
        <h2>Operations timeline</h2>
        <p className="muted-copy">{error ?? "Operations timeline is unavailable right now. Please retry shortly."}</p>
      </section>
    );
  }

  return (
    <section className="panel table-panel">
      <div className="status-row">
        <h2>Operations timeline</h2>
        <span className="muted-copy">
          Page {data.page + 1} · {data.returnedCount} event{data.returnedCount === 1 ? "" : "s"}
        </span>
      </div>
      {data.entries.length === 0 ? (
        <p className="muted-copy">No operations events recorded for this tenant yet.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>Category</th>
              <th>Event</th>
              <th>Status</th>
              <th>Reference</th>
              <th>Occurred</th>
            </tr>
          </thead>
          <tbody>
            {data.entries.map((entry) => (
              <TimelineRow key={`${entry.eventType}-${entry.referenceId}-${entry.occurredAt}`} entry={entry} />
            ))}
          </tbody>
        </table>
      )}
      <TimelinePager tenantId={tenantId} page={data.page} size={data.pageSize} hasMore={data.hasMore} />
    </section>
  );
}

export function SupportOperationsSummarySkeleton() {
  return (
    <section className="panel" aria-busy="true" aria-label="Loading operations summary">
      <h2>Operations summary</h2>
      <p className="muted-copy">Loading operations summary…</p>
    </section>
  );
}

export function SupportOperationsTimelineSkeleton() {
  return (
    <section className="panel" aria-busy="true" aria-label="Loading operations timeline">
      <h2>Operations timeline</h2>
      <p className="muted-copy">Loading operations timeline…</p>
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
      <td>{entry.referenceId}</td>
      <td>{formatTimestamp(entry.occurredAt)}</td>
    </tr>
  );
}

// Pagination uses plain links carrying only the selected tenant navigation handle plus the safe bounded
// page/size locators — never any authority, actor, status, or approval field. The links are rendered as
// inert spans at the page edges.
function TimelinePager({
  tenantId,
  page,
  size,
  hasMore
}: Readonly<{ tenantId: string; page: number; size: number; hasMore: boolean }>) {
  const href = (target: number) =>
    `/internal-support/operations?tenantId=${encodeURIComponent(tenantId)}&page=${target}&size=${size}`;
  return (
    <div className="status-row" aria-label="Timeline pagination">
      {page > 0 ? (
        <Link className="button table-link-button" href={href(page - 1)}>
          Newer
        </Link>
      ) : (
        <span className="muted-copy">Newer</span>
      )}
      {hasMore ? (
        <Link className="button table-link-button" href={href(page + 1)}>
          Older
        </Link>
      ) : (
        <span className="muted-copy">Older</span>
      )}
    </div>
  );
}

function SummaryCell({ label, value }: Readonly<{ label: string; value: number }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{Number.isFinite(value) ? value : 0}</strong>
    </div>
  );
}

function formatTimestamp(value: string | null | undefined): string {
  if (typeof value !== "string" || value.trim() === "") {
    return "—";
  }
  return value;
}
