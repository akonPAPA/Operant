import Link from "next/link";

import type {
  CommerceIntelligenceDemoFlow,
  CommerceIntelligenceSafety
} from "@/lib/commerce-intelligence-api";

type Props = {
  data: CommerceIntelligenceDemoFlow | null;
  error?: string;
};

function formatTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "Not available" : parsed.toLocaleString();
}

function measured(value: number | null, safety: CommerceIntelligenceSafety): string {
  return value === null ? safety.measurementScope : String(value);
}

function humanize(value: string | null): string {
  return value ? value.toLowerCase().replaceAll("_", " ") : "Not available";
}

export function CommerceIntelligenceDemoFlowView({ data, error }: Props) {
  if (!data) {
    return (
      <section className="panel">
        <h2>Commerce Intelligence unavailable</h2>
        <p className="risk-note">{error ?? "No tenant-observed demo-flow data is available."}</p>
        <div className="button-row">
          <Link className="button secondary" href="/demo">Open demo</Link>
          <Link className="button secondary" href="/channels/rfq-handoffs">Open RFQ handoffs</Link>
        </div>
      </section>
    );
  }

  const { summary, safety, runtimeControl } = data;
  const summaryCards = [
    ["RFQs captured", summary.rfqHandoffsTotal],
    ["Pending review", summary.pendingReviewCount],
    ["In review", summary.inReviewCount],
    ["AI advisory suggestions", summary.aiAdvisorySuggestionsCount],
    ["Review-required draft quotes", summary.reviewRequiredDraftQuotesCount],
    ["Safe demo terminal decisions", summary.safeTerminalDemoDecisionsCount]
  ] as const;

  return (
    <div className="demo-stack">
      {error ? <p className="risk-note">{error}</p> : null}

      <section className="panel">
        <h2>RFQ demo-flow visibility</h2>
        <p>
          Read-only, tenant-scoped facts from RFQ capture through advisory AI, operator review,
          review-required draft creation, and safe demo terminal decisions.
        </p>
        <p className="muted-copy">
          {data.windowLabel}. Generated {formatTime(data.generatedAt)}.
        </p>
        <div className="button-row">
          <Link className="button secondary" href="/demo">Open demo</Link>
          <Link className="button secondary" href="/channels/rfq-handoffs">Open RFQ handoffs</Link>
        </div>
      </section>

      <div className="page-grid">
        {summaryCards.map(([label, value]) => (
          <section className="panel" key={label}>
            <h2>{value}</h2>
            <p>{label}</p>
          </section>
        ))}
      </div>

      <section className="panel table-panel">
        <h2>Safety state</h2>
        <p className="status-pill done">
          Read-only intelligence · external execution disabled · no connector invoked
        </p>
        <p>{safety.safetyStatement}</p>
        <table className="data-table">
          <thead>
            <tr><th>Boundary</th><th>State</th><th>Observed rows</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>External writes</td>
              <td><span className="status-pill done">{safety.externalWriteStatus}</span></td>
              <td>Not a row-count metric</td>
            </tr>
            <tr>
              <td>Connector call</td>
              <td><span className="status-pill done">{safety.connectorCallStatus}</span></td>
              <td>{measured(safety.observedConnectorCommandRows, safety)}</td>
            </tr>
            <tr>
              <td>Change request</td>
              <td><span className="status-pill done">NOT_REQUESTED</span></td>
              <td>{measured(safety.observedChangeRequestRows, safety)}</td>
            </tr>
            <tr>
              <td>Outbox</td>
              <td><span className="status-pill done">{safety.outboxStatus}</span></td>
              <td>{measured(safety.observedOutboxRows, safety)}</td>
            </tr>
          </tbody>
        </table>
        <ul>
          {safety.notProven.map((item) => <li key={item}>{item}</li>)}
        </ul>
      </section>

      <section className="panel table-panel">
        <h2>Runtime-control posture</h2>
        <p>{runtimeControl.note}</p>
        <table className="data-table">
          <thead>
            <tr><th>Path step</th><th>Safe posture</th></tr>
          </thead>
          <tbody>
            <tr><td>Demo RFQ creation</td><td>{humanize(runtimeControl.demoRfqHandoffCreate)}</td></tr>
            <tr><td>RFQ AI advisory</td><td>{humanize(runtimeControl.rfqHandoffAiAdvisory)}</td></tr>
            <tr><td>Draft quote creation</td><td>{humanize(runtimeControl.draftQuoteCreate)}</td></tr>
            <tr><td>Safe demo decision</td><td>{humanize(runtimeControl.safeDemoDecision)}</td></tr>
            <tr><td>Demo billing / quota / feature dimension</td><td>{humanize(runtimeControl.billingOrQuotaDimension)}</td></tr>
            <tr><td>Denial telemetry</td><td>{humanize(runtimeControl.denialTelemetry)}</td></tr>
          </tbody>
        </table>
      </section>

      <section className="panel table-panel">
        <h2>Blocking bottlenecks</h2>
        {data.bottlenecks.length === 0 ? (
          <p>No open blocking issues were observed for RFQ-handoff draft quotes.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr><th>Issue</th><th>Code</th><th>Count</th><th>Meaning</th></tr>
            </thead>
            <tbody>
              {data.bottlenecks.map((item) => (
                <tr key={item.code}>
                  <td>{item.label}</td>
                  <td>{item.code}</td>
                  <td>{item.count}</td>
                  <td>{item.explanation}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel table-panel">
        <h2>Recent demo flows</h2>
        {data.recentFlows.length === 0 ? (
          <p>No RFQ handoff flows are available for this tenant.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Created</th>
                <th>Channel</th>
                <th>Request preview</th>
                <th>Intent</th>
                <th>Handoff</th>
                <th>AI schema / risk</th>
                <th>Draft</th>
                <th>Validation</th>
                <th>Terminal</th>
              </tr>
            </thead>
            <tbody>
              {data.recentFlows.map((flow) => (
                <tr key={flow.handoffId}>
                  <td>{formatTime(flow.createdAt)}</td>
                  <td>{flow.sourceChannel}</td>
                  <td>{flow.requestPreview}</td>
                  <td>{humanize(flow.detectedIntent)}</td>
                  <td>{humanize(flow.handoffStatus)}</td>
                  <td>
                    {flow.aiSchemaVersion ?? humanize(flow.aiSuggestionStatus)}
                    {flow.aiRiskLevel ? <><br /><span className="muted-copy">Risk: {humanize(flow.aiRiskLevel)}</span></> : null}
                  </td>
                  <td>{humanize(flow.draftQuoteStatus)}</td>
                  <td>
                    {humanize(flow.validationStatus)}
                    {flow.blockingIssueCodes.length > 0 ? (
                      <><br /><span className="muted-copy">{flow.blockingIssueCodes.join(", ")}</span></>
                    ) : null}
                  </td>
                  <td>{humanize(flow.safeTerminalState)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel">
        <h2>Not proven by this read model</h2>
        <ul>
          {data.notProven.map((item) => (
            <li key={item.code}><strong>{item.label}:</strong> {item.explanation}</li>
          ))}
        </ul>
        <p className="risk-note">
          Demo completion or decline is not quote approval, a real order, sale, revenue event,
          invoice, ERP sync, or customer commitment.
        </p>
      </section>
    </div>
  );
}
