import { RoiAssumptionsPanel } from "@/components/roi-assumptions-panel";
import { getStage8PilotReportExportUrl, getStage8RoiAssumptions, getStage8ValueSummary } from "@/lib/stage8-value-api";

export async function BusinessValueAnalytics() {
  const [summary, assumptions] = await Promise.all([
    getStage8ValueSummary(),
    getStage8RoiAssumptions()
  ]);
  const exportUrl = getStage8PilotReportExportUrl();
  const currency = summary?.currency ?? assumptions?.defaultCurrency ?? "USD";

  return (
    <>
      <section className="panel">
        <div className="section-heading">
          <h2>Business Value</h2>
          {exportUrl ? (
            <a className="button secondary-button" href={exportUrl}>Export pilot report</a>
          ) : null}
        </div>
        <div className="kpi-grid">
          <Metric label="Estimated hours saved" value={summary?.estimatedOperatorHoursSaved ?? "n/a"} />
          <Metric label="Estimated labor cost saved" value={formatMoney(summary?.estimatedLaborCostSaved, currency)} />
          <Metric label="Exception rate" value="See Commerce Intelligence" />
          <Metric label="Unsafe attempts blocked" value={summary?.blockedUnsafeDraftAttempts ?? "n/a"} />
          <Metric label="Discount leakage" value={`${summary?.discountLeakageCount ?? "n/a"} / ${formatMoney(summary?.estimatedDiscountLeakageValue, currency)}`} />
          <Metric label="Margin risk" value={`${summary?.marginRiskCount ?? "n/a"} / ${formatMoney(summary?.estimatedMarginRiskImpact, currency)}`} />
          <Metric label="Recovered revenue via substitutes" value={formatMoney(summary?.substituteRecoveredRevenue, currency)} />
          <Metric label="Inventory discrepancy value" value={formatMoney(summary?.inventoryDiscrepancyValue, currency)} />
        </div>
        <div className="detail-list">
          <div>
            <dt>Average review cycle time</dt>
            <dd>{summary?.averageReviewCycleHours ?? "n/a"} hours</dd>
          </div>
          <div>
            <dt>Average draft preparation cycle time</dt>
            <dd>{summary?.averageDraftPreparationCycleHours ?? "n/a"} hours</dd>
          </div>
          <div>
            <dt>Stale inventory risk count</dt>
            <dd>{summary?.staleInventoryRiskCount ?? "n/a"}</dd>
          </div>
        </div>
        <p className="risk-note">Estimated values are pilot ROI indicators, not booked revenue. Draft quote/order values are not treated as closed revenue.</p>
      </section>
      <RoiAssumptionsPanel assumptions={assumptions} />
    </>
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

function formatMoney(value?: number | string, currency = "USD") {
  if (value === undefined || value === null) return "n/a";
  return `${currency} ${value}`;
}
