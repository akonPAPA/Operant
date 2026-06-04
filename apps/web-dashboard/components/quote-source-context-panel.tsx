"use client";

import { useState } from "react";

import { getQuoteSourceContext, QuoteSourceContext } from "@/lib/quote-transaction-api";

const demoTenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "11111111-1111-4111-8111-111111111111";

export function QuoteSourceContextPanel({ quoteId }: { quoteId: string }) {
  const [tenantId, setTenantId] = useState(demoTenantId);
  const [context, setContext] = useState<QuoteSourceContext | null>(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    setMessage("");
    try {
      setContext(await getQuoteSourceContext(tenantId, quoteId));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Source context unavailable.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2>Source Context</h2>
      <div className="upload-form">
        <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
        <button className="button secondary-button" disabled={loading} type="button" onClick={load}>{loading ? "Loading..." : "Load Source Context"}</button>
      </div>
      {message ? <p className="form-message error">{message}</p> : null}
      {context ? (
        <dl className="detail-list">
          <div><dt>Source</dt><dd>{context.sourceType} {context.sourceId}</dd></div>
          <div><dt>Channel</dt><dd>{context.sourceChannel ?? "Unknown"}</dd></div>
          <div><dt>External ref</dt><dd>{context.sourceExternalRef ?? "None"}</dd></div>
          <div><dt>Received</dt><dd>{context.sourceReceivedAt ? new Date(context.sourceReceivedAt).toLocaleString() : "Unknown"}</dd></div>
          <div><dt>Attempt</dt><dd>{context.conversionAttemptId ?? "None"}</dd></div>
          <div><dt>Status</dt><dd>{context.conversionStatus ?? "Unknown"}</dd></div>
          <div><dt>Actor</dt><dd>{context.createdByType ?? "Unknown"} {context.triggeredBy ?? ""}</dd></div>
          <div><dt>Validation</dt><dd>{context.validationIssues.map((issue) => issue.code).join(", ") || "No source conversion issues"}</dd></div>
        </dl>
      ) : null}
    </section>
  );
}
