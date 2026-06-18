"use client";

import { useState } from "react";

import {
  ChannelToQuoteResponse,
  createQuoteFromChannelMessage,
  createQuoteFromInboundDocument
} from "@/lib/quote-transaction-api";

const demoTenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "11111111-1111-4111-8111-111111111111";

type Props = {
  sourceId: string;
  sourceType: "CHANNEL_MESSAGE" | "INBOUND_DOCUMENT";
};

export function ChannelQuoteConversionPanel({ sourceId, sourceType }: Props) {
  const [tenantId, setTenantId] = useState(demoTenantId);
  const [customerId, setCustomerId] = useState("");
  const [dryRun, setDryRun] = useState(true);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ChannelToQuoteResponse | null>(null);
  const [message, setMessage] = useState("");

  async function submit() {
    setLoading(true);
    setMessage("");
    try {
      const payload = {
        idempotencyKey: `${sourceType.toLowerCase()}-${sourceId}-${dryRun ? "preview" : "draft"}`,
        requestedCustomerAccountId: customerId || undefined,
        dryRun
      };
      const response = sourceType === "CHANNEL_MESSAGE"
        ? await createQuoteFromChannelMessage(tenantId, sourceId, payload)
        : await createQuoteFromInboundDocument(tenantId, sourceId, payload);
      setResult(response);
      if (response.quoteId) {
        setMessage(response.reviewRequired ? "Draft quote created for review." : "Draft quote created.");
      } else if (response.status.startsWith("REJECTED")) {
        setMessage("Conversion rejected by backend validation.");
      } else if (response.reviewRequired) {
        setMessage("Review required before draft quote creation.");
      } else {
        setMessage("Conversion preview ready.");
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Quote conversion failed.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2>Channel-to-Quote</h2>
      <div className="upload-form">
        <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
        <label><span>Customer account ID</span><input value={customerId} onChange={(event) => setCustomerId(event.target.value)} placeholder="Required unless source resolves customer" /></label>
        <label className="checkbox-row"><input type="checkbox" checked={dryRun} onChange={(event) => setDryRun(event.target.checked)} /> <span>Dry run preview</span></label>
        <button className="button" disabled={loading} type="button" onClick={submit}>{loading ? "Preparing..." : dryRun ? "Prepare Quote" : "Create Quote Draft"}</button>
      </div>
      {message ? <p className={result?.quoteId ? "form-message done" : "form-message"}>{message}</p> : null}
      {result ? (
        <div className="result-panel">
          <p>Status: {result.status}</p>
          <p>Quote: {result.quoteId ?? "Not created"}</p>
          <p>Attempt: {result.conversionAttemptId}</p>
          <p>Customer: {result.customerResolution ?? "Unknown"}</p>
          <p>Lines: {result.acceptedLineCount} accepted / {result.lineCount} detected</p>
          <p>Review required: {result.reviewRequired ? "Yes" : "No"}</p>
          {result.validationIssues.length ? (
            <table className="data-table"><thead><tr><th>Issue</th><th>Severity</th><th>Blocking</th></tr></thead><tbody>
              {result.validationIssues.map((issue) => <tr key={`${issue.code}-${issue.lineId ?? "source"}`}><td>{issue.code}</td><td>{issue.severity}</td><td>{issue.blocking ? "Yes" : "No"}</td></tr>)}
            </tbody></table>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
