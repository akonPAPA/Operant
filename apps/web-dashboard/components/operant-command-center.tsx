import {
  getCommandCenterSummary,
  type AuditTimelineItem,
  type CommandCenterMetric,
  type ReconciliationPreviewCase,
  type WorkQueueItem
} from "@/lib/command-center-api";

// OP-CAP-21 — backend-backed Operant command center surface. Server component: it consumes the
// tenant-scoped read-only summary projection and renders honest loading/empty/partial/unavailable
// states. It never fabricates metrics and never renders mutation controls.
export async function OperantCommandCenter() {
  const { data, error } = await getCommandCenterSummary();

  if (!data) {
    return (
      <section className="panel">
        <h2>Transaction Command Center</h2>
        <div className="status-row">
          <span className="status-dot" aria-hidden="true" />
          <p>{error ?? "Command center projection not connected yet."}</p>
        </div>
        <p className="risk-note">Read-only tenant-scoped projection. No data is fabricated when the backend is unavailable.</p>
      </section>
    );
  }

  return (
    <>
      <section className="panel">
        <h2>Command Center metrics</h2>
        <div className="kpi-grid">
          {data.metrics.map((metric) => (
            <Metric key={metric.key} metric={metric} />
          ))}
        </div>
        <p className="risk-note">Derived read models from existing tenant-scoped services. Metrics marked unavailable have no production data source yet.</p>
      </section>

      <section className="panel">
        <h2>Work queue preview</h2>
        {data.workQueue.items.length === 0 ? (
          <p>No open review cases for this tenant yet.</p>
        ) : (
          <ul className="detail-list">
            {data.workQueue.items.map((item) => (
              <WorkQueueRow key={item.caseId} item={item} />
            ))}
          </ul>
        )}
        <p className="risk-note">
          {data.workQueue.openTotal} open total
          {data.workQueue.partial ? ` — showing the ${data.workQueue.previewLimit} most recent` : ""}.
        </p>
      </section>

      <section className="panel">
        <h2>Runtime &amp; outbox health</h2>
        <div className="kpi-grid">
          <StatCard label="Jobs pending" value={data.runtime.available ? data.runtime.pendingJobs : "n/a"} />
          <StatCard label="Jobs running" value={data.runtime.available ? data.runtime.runningJobs : "n/a"} />
          <StatCard label="Jobs failed" value={data.runtime.available ? data.runtime.failedJobs : "n/a"} flag={data.runtime.degraded} />
          <StatCard label="Outbox pending" value={data.outbox.available ? data.outbox.pendingEvents : "n/a"} flag={data.outbox.degraded} />
          <StatCard label="Outbox published" value={data.outbox.available ? data.outbox.publishedEvents : "n/a"} />
          <StatCard label="Outbox skipped (external disabled)" value={data.outbox.available ? data.outbox.skippedExternalDisabled : "n/a"} />
        </div>
        {!data.runtime.available ? <p className="risk-note">{data.runtime.note ?? "No processing jobs yet."}</p> : null}
        {!data.outbox.available ? <p className="risk-note">{data.outbox.note ?? "No outbox events yet."}</p> : null}
      </section>

      <section className="panel">
        <h2>Audit timeline preview</h2>
        {data.auditTimeline.items.length === 0 ? (
          <p>No audit events for this tenant yet.</p>
        ) : (
          <ul className="detail-list">
            {data.auditTimeline.items.map((item, index) => (
              <AuditRow key={`${item.action}-${item.occurredAt}-${index}`} item={item} />
            ))}
          </ul>
        )}
        <p className="risk-note">Action classifications only. No audit identifiers or detail blobs are exposed to the frontend.</p>
      </section>

      <section className="panel">
        <h2>Reconciliation preview</h2>
        {!data.reconciliation.available ? (
          <p>Reconciliation projection unavailable.</p>
        ) : (
          <>
            <div className="kpi-grid">
              <StatCard label="Open reconciliation cases" value={data.reconciliation.openCases} />
              <StatCard label="High severity open" value={data.reconciliation.highSeverityOpenCases} flag={data.reconciliation.highSeverityOpenCases > 0} />
            </div>
            {data.reconciliation.recentCases.length === 0 ? (
              <p>No reconciliation cases yet.</p>
            ) : (
              <ul className="detail-list">
                {data.reconciliation.recentCases.map((c) => (
                  <ReconciliationRow key={c.caseId} reconCase={c} />
                ))}
              </ul>
            )}
          </>
        )}
        <p className="risk-note">{data.reconciliation.note ?? "Inventory reconciliation domain. No payment-provider reconciliation is implemented."}</p>
      </section>
    </>
  );
}

function Metric({ metric }: Readonly<{ metric: CommandCenterMetric }>) {
  return (
    <div className="kpi-card">
      <span>{metric.label}</span>
      <strong>{metric.available ? metric.value : "Unavailable"}</strong>
      {metric.note ? <small>{metric.note}</small> : null}
    </div>
  );
}

function StatCard({ label, value, flag }: Readonly<{ label: string; value: number | string; flag?: boolean }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}{flag ? " ⚠" : ""}</strong>
    </div>
  );
}

function WorkQueueRow({ item }: Readonly<{ item: WorkQueueItem }>) {
  return (
    <div>
      <dt>{item.caseNumber} · {item.severity}</dt>
      <dd>{item.title} — {item.status} ({item.sourceType})</dd>
    </div>
  );
}

function AuditRow({ item }: Readonly<{ item: AuditTimelineItem }>) {
  return (
    <div>
      <dt>{item.action}</dt>
      <dd>{item.entityType} · {item.occurredAt}</dd>
    </div>
  );
}

function ReconciliationRow({ reconCase }: Readonly<{ reconCase: ReconciliationPreviewCase }>) {
  return (
    <div>
      <dt>{reconCase.severity} · {reconCase.status}</dt>
      <dd>Mismatch {reconCase.mismatchQuantity} — product {reconCase.productId}</dd>
    </div>
  );
}
