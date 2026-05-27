import { getStage8CommandCenterAnalytics, getStage8ReconciliationSummary } from "@/lib/stage8-analytics-api";

export async function CommandCenterAnalytics() {
  const [analytics, reconciliation] = await Promise.all([
    getStage8CommandCenterAnalytics(),
    getStage8ReconciliationSummary()
  ]);
  const channelMix = analytics?.channelMix ?? {};

  return (
    <section className="panel">
      <h2>Commerce Intelligence</h2>
      <div className="kpi-grid">
        <Metric label="Total inbound requests" value={analytics?.totalInboundRequests ?? "n/a"} />
        <Metric label="Bot handoffs" value={analytics?.botOnlyHandoffCount ?? "n/a"} />
        <Metric label="Validation-backed reviews" value={analytics?.validationBackedReviewCount ?? "n/a"} />
        <Metric label="Blocked unsafe draft attempts" value={analytics?.blockedUnsafeDraftAttempts ?? "n/a"} />
        <Metric label="Exception rate" value={formatRate(analytics?.exceptionRate)} />
        <Metric label="Drafts prepared" value={analytics?.draftsPrepared ?? "n/a"} />
        <Metric label="Inventory mismatch count" value={reconciliation?.inventoryMismatchCount ?? "n/a"} />
        <Metric label="High severity discrepancy count" value={reconciliation?.highSeverityDiscrepancyCount ?? "n/a"} />
        <Metric label="Stale inventory count" value={reconciliation?.staleInventoryCount ?? "n/a"} />
        <Metric label="Low stock count" value={reconciliation?.lowStockCount ?? "n/a"} />
      </div>
      <div className="detail-list">
        <div>
          <dt>Channel mix</dt>
          <dd>{Object.entries(channelMix).length === 0 ? "No channel volume yet" : Object.entries(channelMix).map(([channel, count]) => `${channel}: ${count}`).join(" | ")}</dd>
        </div>
      </div>
      <p className="risk-note">Stage 8A analytics are tenant-scoped read models. Bot handoffs remain separate from validation-backed reviews.</p>
    </section>
  );
}

function Metric({ label, value }: Readonly<{ label: string; value: number | string }>) {
  return (
    <div className="kpi-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function formatRate(value?: number | string) {
  if (value === undefined || value === null) return "n/a";
  return `${value}%`;
}
