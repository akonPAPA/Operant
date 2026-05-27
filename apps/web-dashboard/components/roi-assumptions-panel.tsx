import type { RoiAssumptions } from "@/lib/stage8-value-api";

export function RoiAssumptionsPanel({ assumptions }: Readonly<{ assumptions: RoiAssumptions | null }>) {
  return (
    <section className="panel">
      <h2>ROI assumptions</h2>
      <div className="detail-list">
        <div>
          <dt>Manual handling time</dt>
          <dd>{assumptions?.averageManualHandlingMinutesPerRequest ?? "n/a"} minutes per request</dd>
        </div>
        <div>
          <dt>Operator hourly cost</dt>
          <dd>{formatMoney(assumptions?.averageFullyLoadedOperatorHourlyCost, assumptions?.defaultCurrency)}</dd>
        </div>
        <div>
          <dt>Attribution mode</dt>
          <dd>{assumptions?.valueAttributionMode ?? "n/a"}</dd>
        </div>
      </div>
      <p className="risk-note">
        {assumptions?.defaultAssumptions
          ? "Estimated values use safe demo defaults until tenant ROI assumptions are saved."
          : "Estimated values use tenant-specific ROI assumptions."}
      </p>
    </section>
  );
}

function formatMoney(value?: number | string, currency = "USD") {
  if (value === undefined || value === null) return "n/a";
  return `${currency} ${value}`;
}
