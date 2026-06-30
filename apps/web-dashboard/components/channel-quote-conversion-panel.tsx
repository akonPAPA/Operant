"use client";

import { useState } from "react";

import {
  ChannelToQuotePayload,
  ChannelToQuoteResponse,
  createQuoteFromChannelMessage,
  createQuoteFromInboundDocument
} from "@/lib/quote-transaction-api";
import {
  createOperatorIdempotencyKey,
  OperatorActionResult,
  useOperatorAction
} from "@/lib/operator-action-runtime";

// OP-CAP-33: tenant is resolved from env/config, not from an editable operator input.
// The backend validates the X-Tenant-Id header — the UI must not offer a tenant override.
type Props = {
  sourceId: string;
  sourceType: "CHANNEL_MESSAGE" | "INBOUND_DOCUMENT";
};

export function ChannelQuoteConversionPanel({ sourceId, sourceType }: Props) {
  const [customerId, setCustomerId] = useState("");
  const [dryRun, setDryRun] = useState(true);
  const [result, setResult] = useState<ChannelToQuoteResponse | null>(null);
  const [message, setMessage] = useState("");

  const actionName =
    sourceType === "CHANNEL_MESSAGE" ? "channel-to-quote" : "document-to-quote";

  const { execute, pending, disabled } = useOperatorAction<ChannelToQuoteResponse>({
    onSuccess: (_data, safeMessage) => setMessage(safeMessage),
    onError: (_errorCode, safeMessage) => setMessage(safeMessage)
  });

  async function submit() {
    setMessage("");
    // Deterministic key: same source + same mode (preview vs draft) reuses the key
    // so a retry after a lost response is deduped by the backend instead of creating
    // a duplicate. Preview and draft are kept distinct so they never collide.
    const idempotencyKey = createOperatorIdempotencyKey(
      actionName,
      `${sourceId}-${dryRun ? "preview" : "draft"}`
    );

    await execute(async (): Promise<OperatorActionResult<ChannelToQuoteResponse>> => {
      const payload: ChannelToQuotePayload = {
        idempotencyKey,
        requestedCustomerAccountId: customerId || undefined,
        dryRun
      };
      const response = sourceType === "CHANNEL_MESSAGE"
        ? await createQuoteFromChannelMessage(sourceId, payload)
        : await createQuoteFromInboundDocument(sourceId, payload);

      setResult(response);
      const safeMessage = computeConversionMessage(response);
      return { ok: true, data: response, safeMessage };
    });
  }

  return (
    <section className="panel">
      <h2>Channel-to-Quote</h2>
      <div className="upload-form">
        <p className="tenant-context-info">Tenant scope: explicit local demo mode</p>
        <label><span>Customer account ID</span><input value={customerId} onChange={(event) => setCustomerId(event.target.value)} placeholder="Required unless source resolves customer" /></label>
        <label className="checkbox-row"><input type="checkbox" checked={dryRun} onChange={(event) => setDryRun(event.target.checked)} /> <span>Dry run preview</span></label>
        <button className="button" disabled={disabled} type="button" onClick={submit}>{pending ? "Preparing..." : dryRun ? "Prepare Quote" : "Create Quote Draft"}</button>
      </div>
      {message ? <p className={result?.quoteId ? "form-message done" : "form-message"}>{message}</p> : null}
      {result ? (
        <div className="result-panel">
          <p>Status: {result.status}</p>
          <p>Quote: {result.quoteId ?? "Not created"}</p>
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

function computeConversionMessage(response: ChannelToQuoteResponse): string {
  if (response.quoteId) {
    return response.reviewRequired
      ? "Draft quote created for review."
      : "Draft quote created.";
  }
  if (response.status.startsWith("REJECTED")) {
    return "Conversion rejected by backend validation.";
  }
  if (response.reviewRequired) {
    return "Review required before draft quote creation.";
  }
  return "Conversion preview ready.";
}
