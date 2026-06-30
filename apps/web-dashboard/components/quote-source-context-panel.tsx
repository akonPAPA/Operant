"use client";

import { useState } from "react";

import { getQuoteSourceContext, QuoteSourceContext } from "@/lib/quote-transaction-api";

// OP-CAP-31: tenant is supplied to the API client (X-Tenant-Id header) from configuration, not from
// an editable operator input. This panel renders a business-facing source summary only — no raw
// sourceId, conversionAttemptId, or internal actor/user identifiers.
export function QuoteSourceContextPanel({ quoteId }: { quoteId: string }) {
  const [context, setContext] = useState<QuoteSourceContext | null>(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    setMessage("");
    try {
      setContext(await getQuoteSourceContext(quoteId));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Source summary unavailable.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2>Request Source Summary</h2>
      <div className="upload-form">
        <button className="button secondary-button" disabled={loading} type="button" onClick={load}>{loading ? "Loading..." : "Load Source Summary"}</button>
      </div>
      {message ? <p className="form-message error">{message}</p> : null}
      {context ? (
        <dl className="detail-list">
          <div><dt>Source</dt><dd>{context.sourceType}</dd></div>
          <div><dt>Channel</dt><dd>{context.sourceChannel ?? "Unknown"}</dd></div>
          <div><dt>External ref</dt><dd>{context.sourceExternalRef ?? "None"}</dd></div>
          <div><dt>Received</dt><dd>{context.sourceReceivedAt ? new Date(context.sourceReceivedAt).toLocaleString() : "Unknown"}</dd></div>
          <div><dt>Status</dt><dd>{context.conversionStatus ?? "Unknown"}</dd></div>
          <div><dt>Lines detected</dt><dd>{context.candidateLineCount}</dd></div>
          <div><dt>Review required</dt><dd>{context.reviewRequired ? "Yes" : "No"}</dd></div>
          <div><dt>Validation</dt><dd>{context.validationIssues.map((issue) => issue.code).join(", ") || "No source conversion issues"}</dd></div>
        </dl>
      ) : null}
    </section>
  );
}
