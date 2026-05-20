"use client";

import { useMemo, useState } from "react";
import {
  type ApiResult,
  type BotWebhookResponse,
  type CommerceAnalyticsSummaryResponse,
  type ReconciliationCasesResponse,
  type ReconciliationRunResponse,
  demoConfig,
  demoTelegramRfqPayload,
  reconciliationFixture,
  refreshCommerceAnalytics,
  runInventoryReconciliation,
  sendDemoTelegramRfq,
  sendUnknownTelegramMessage,
  viewReconciliationCases
} from "@/lib/demo-api";

type DemoActionState = {
  label: string;
  result?: ApiResult<unknown>;
};

const timeline = [
  "Telegram RFQ received",
  "Bot classifies RFQ_REQUEST",
  "RFQ draft/request created for review",
  "Unknown message routes to human handoff",
  "Inventory reconciliation detects mismatch",
  "Analytics summary updates",
  "Audit/security model explains why this is safe"
];

const trustBullets = [
  "AI/bot cannot approve a quote.",
  "Bot cannot create a final order.",
  "Bot cannot update inventory, prices, or customers.",
  "No ERP write exists in this demo surface.",
  "Important actions are audit logged through core-api services.",
  "Tenant isolation uses the X-Tenant-Id boundary."
];

export function DemoDashboard() {
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [actions, setActions] = useState<DemoActionState[]>([]);
  const [rfqResult, setRfqResult] = useState<BotWebhookResponse | null>(null);
  const [unknownResult, setUnknownResult] = useState<BotWebhookResponse | null>(null);
  const [reconciliationResult, setReconciliationResult] = useState<ReconciliationRunResponse | null>(null);
  const [casesResult, setCasesResult] = useState<ReconciliationCasesResponse | null>(null);
  const [analyticsResult, setAnalyticsResult] = useState<CommerceAnalyticsSummaryResponse | null>(null);

  const kpis = useMemo(() => {
    const channelBreakdown = analyticsResult?.channelBreakdown ?? {};
    return [
      { label: "Bot RFQ requests", value: analyticsResult?.totalBotRfqRequests ?? (rfqResult ? 1 : 0) },
      { label: "Human handoffs", value: unknownResult?.requiresHumanReview ? 1 : 0 },
      { label: "Open reconciliation cases", value: analyticsResult?.openReconciliationCases ?? casesResult?.totalElements ?? (reconciliationResult ? 1 : 0) },
      { label: "High severity cases", value: analyticsResult?.highSeverityReconciliationCases ?? (reconciliationResult?.severity === "HIGH" ? 1 : 0) },
      { label: "Telegram channel count", value: channelBreakdown.TELEGRAM ?? (rfqResult && unknownResult ? 2 : rfqResult ? 1 : 0) },
      { label: "Automation/demo status", value: demoConfig.tenantId ? "Connected" : "Fixture mode" }
    ];
  }, [analyticsResult, casesResult, reconciliationResult, rfqResult, unknownResult]);

  async function runAction<T>(label: string, action: () => Promise<ApiResult<T>>, onSuccess?: (data: T) => void) {
    setBusyAction(label);
    const result = await action();
    if (result.ok) {
      onSuccess?.(result.data);
    }
    setActions((current) => [{ label, result }, ...current].slice(0, 5));
    setBusyAction(null);
  }

  return (
    <div className="demo-stack">
      <section className="demo-hero">
        <div>
          <span className="eyebrow">OrderPilot Demo Parts Distributor</span>
          <h2>B2B auto and industrial parts distributor demo</h2>
          <p>
            Messy Telegram, PDF, and Excel requests become validated drafts or human handoffs, then flow into
            reconciliation, analytics, audit, and security controls.
          </p>
        </div>
        <div className="demo-context">
          <strong>Core API</strong>
          <span>{demoConfig.baseUrl}</span>
          <strong>Tenant header</strong>
          <span>{demoConfig.tenantId || "Not configured; demo fixture mode is visible."}</span>
        </div>
      </section>

      <section className="panel">
        <h2>Demo flow timeline</h2>
        <ol className="demo-timeline">
          {timeline.map((step, index) => (
            <li key={step}>
              <span>{index + 1}</span>
              <strong>{step}</strong>
            </li>
          ))}
        </ol>
      </section>

      <section className="kpi-grid" aria-label="Investor demo KPIs">
        {kpis.map((kpi) => (
          <div className="kpi-card" key={kpi.label}>
            <span>{kpi.label}</span>
            <strong>{kpi.value}</strong>
          </div>
        ))}
      </section>

      <section className="panel">
        <div className="section-heading">
          <div>
            <h2>Demo controls</h2>
            <p>Calls are demo-only browser requests to core-api. Missing seed/config is handled as a visible demo limitation.</p>
          </div>
        </div>
        <div className="button-row">
          <button className="button" disabled={busyAction !== null} onClick={() => runAction("Send demo Telegram RFQ", sendDemoTelegramRfq, setRfqResult)}>
            {busyAction === "Send demo Telegram RFQ" ? "Sending..." : "Send demo Telegram RFQ"}
          </button>
          <button className="button secondary-button" disabled={busyAction !== null} onClick={() => runAction("Send unknown message", sendUnknownTelegramMessage, setUnknownResult)}>
            Send unknown message
          </button>
          <button className="button secondary-button" disabled={busyAction !== null} onClick={() => runAction("Run inventory reconciliation", runInventoryReconciliation, setReconciliationResult)}>
            Run inventory reconciliation
          </button>
          <button className="button secondary-button" disabled={busyAction !== null} onClick={() => runAction("Refresh analytics", refreshCommerceAnalytics, setAnalyticsResult)}>
            Refresh analytics
          </button>
          <button className="button secondary-button" disabled={busyAction !== null} onClick={() => runAction("View reconciliation cases", viewReconciliationCases, setCasesResult)}>
            View reconciliation cases
          </button>
        </div>
        <div className="debug-panel">
          <strong>Last demo calls</strong>
          {actions.length === 0 ? <p>No calls made yet.</p> : actions.map((action) => <p key={`${action.label}-${action.result?.message}`}>{action.label}: {action.result?.message}</p>)}
        </div>
      </section>

      <div className="two-column">
        <section className="panel">
          <h2>Telegram RFQ panel</h2>
          <blockquote>{demoTelegramRfqPayload.message.text}</blockquote>
          <dl className="detail-list">
            <div><dt>Detected intent</dt><dd>{rfqResult?.intent ?? "RFQ_REQUEST fixture"}</dd></div>
            <div><dt>Requires human review</dt><dd>{String(rfqResult?.requiresHumanReview ?? true)}</dd></div>
            <div><dt>RFQ draft/request ID</dt><dd>{rfqResult?.createdRfqDraftId ?? "Available after backend seed and button run"}</dd></div>
            <div><dt>Audit status</dt><dd>BOT_RFQ_DRAFT_CREATED when backend flow succeeds</dd></div>
          </dl>
        </section>

        <section className="panel">
          <h2>Reconciliation panel</h2>
          <dl className="metric-list">
            <div><dt>Opening stock</dt><dd>{reconciliationFixture.openingStock}</dd></div>
            <div><dt>Sold</dt><dd>{reconciliationFixture.sold}</dd></div>
            <div><dt>Expected stock</dt><dd>{reconciliationResult?.expectedStock ?? reconciliationFixture.expectedStock}</dd></div>
            <div><dt>Actual stock</dt><dd>{reconciliationResult?.actualStock ?? reconciliationFixture.actualStock}</dd></div>
            <div><dt>Mismatch</dt><dd>{reconciliationResult?.mismatchQuantity ?? reconciliationFixture.mismatch}</dd></div>
            <div><dt>Severity</dt><dd><span className="severity-badge">{reconciliationResult?.severity ?? reconciliationFixture.severity}</span></dd></div>
          </dl>
          <p className="muted-copy">{reconciliationFixture.likelyCauses}</p>
        </section>
      </div>

      <div className="two-column">
        <section className="panel">
          <h2>Analytics summary panel</h2>
          <dl className="detail-list">
            <div><dt>Total sales amount</dt><dd>{analyticsResult?.totalSalesAmount ?? 0}</dd></div>
            <div><dt>Sales note</dt><dd>{analyticsResult?.totalSalesAmountNote ?? "No invoice/sales mirror records in the local demo yet."}</dd></div>
            <div><dt>Bot RFQ count</dt><dd>{analyticsResult?.totalBotRfqRequests ?? "Refresh after sending RFQ"}</dd></div>
            <div><dt>Open/high reconciliation</dt><dd>{analyticsResult ? `${analyticsResult.openReconciliationCases}/${analyticsResult.highSeverityReconciliationCases}` : "Refresh after reconciliation"}</dd></div>
            <div><dt>Channel breakdown</dt><dd>TELEGRAM: {analyticsResult?.channelBreakdown?.TELEGRAM ?? "0 until backend flow runs"}</dd></div>
          </dl>
        </section>

        <section className="panel trust-panel">
          <h2>Security and trust panel</h2>
          <ul>
            {trustBullets.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </section>
      </div>

      <section className="panel">
        <h2>Demo backend data fallback</h2>
        <p>
          If calls return errors, seed or run the backend demo flow from <code>docs/investor/demo-api-walkthrough.http</code>.
          The page keeps showing labeled fixtures so the investor story remains explainable without pretending production data exists.
        </p>
      </section>
    </div>
  );
}
