"use client";

import { FormEvent, useRef, useState } from "react";

import {
  approveQuote,
  convertQuoteToInternalOrder,
  createDraftQuoteFromRfq,
  getQuoteApprovalState,
  QuoteApprovalCommandResponse,
  QuoteApprovalState,
  QuoteTransactionResponse,
  rejectQuote,
  requestQuoteChanges
} from "@/lib/quote-transaction-api";
import { generateIdempotencyKey } from "@/lib/security-idempotency";

export function QuoteWorkspace() {
  const [result, setResult] = useState<QuoteTransactionResponse | null>(null);
  const [approvalState, setApprovalState] = useState<QuoteApprovalState | null>(null);
  const [approvalResult, setApprovalResult] = useState<QuoteApprovalCommandResponse | null>(null);
  const [decisionReason, setDecisionReason] = useState("");
  const [message, setMessage] = useState<string>("");
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const createDraftKeyRef = useRef<string | null>(null);
  const approvalActionKeyRef = useRef<Map<string, string>>(new Map());

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loadingRef.current) return;
    if (!createDraftKeyRef.current) {
      try {
        createDraftKeyRef.current = generateIdempotencyKey();
      } catch (error) {
        setMessage(error instanceof Error ? error.message : "Secure idempotency key generation failed.");
        return;
      }
    }
    loadingRef.current = true;
    setLoading(true);
    setMessage("");
    const form = new FormData(event.currentTarget);
    try {
      const response = await createDraftQuoteFromRfq({
        customerExternalRef: String(form.get("customerExternalRef") || "CUST-001"),
        requestedLocation: String(form.get("requestedLocation") || "WH-ALM"),
        requestedDiscountPercent: Number(form.get("requestedDiscountPercent") || 0),
        idempotencyKey: createDraftKeyRef.current,
        requestedItems: [{
          rawSkuOrAlias: String(form.get("rawSkuOrAlias") || "PAD-OE-04465"),
          description: String(form.get("description") || "Original brake pads for Toyota Camry 2018"),
          quantity: Number(form.get("quantity") || 2),
          uom: String(form.get("uom") || "EA")
        }]
      });
      setResult(response);
      setApprovalResult(null);
      createDraftKeyRef.current = null;
      setApprovalState(await getQuoteApprovalState(response.draftQuoteId));
      setMessage("Draft quote created through the backend transaction service.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Quote request failed.");
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

  async function runApprovalAction(action: "approve" | "reject" | "changes" | "convert") {
    if (!result) return;
    if (loadingRef.current) return;
    if ((action === "reject" || action === "changes") && !decisionReason.trim()) {
      setMessage("Reason/comment is required for reject or request changes.");
      return;
    }
    const actionKey = `${action}-${result.draftQuoteId}`;
    if (!approvalActionKeyRef.current.has(actionKey)) {
      try {
        approvalActionKeyRef.current.set(actionKey, generateIdempotencyKey());
      } catch (error) {
        setMessage(error instanceof Error ? error.message : "Secure idempotency key generation failed.");
        return;
      }
    }
    loadingRef.current = true;
    setLoading(true);
    setMessage("");
    const payload = {
      reason: decisionReason,
      comment: decisionReason,
      idempotencyKey: approvalActionKeyRef.current.get(actionKey)!
    };
    try {
      const response = action === "approve"
        ? await approveQuote(result.draftQuoteId, payload)
        : action === "reject"
          ? await rejectQuote(result.draftQuoteId, payload)
          : action === "changes"
            ? await requestQuoteChanges(result.draftQuoteId, payload)
            : await convertQuoteToInternalOrder(result.draftQuoteId, payload);
      setApprovalResult(response);
      approvalActionKeyRef.current.delete(actionKey);
      setApprovalState(await getQuoteApprovalState(result.draftQuoteId));
      setResult({ ...result, status: response.newStatus, approvalRequired: response.approvalRequired, approvalReasons: response.approvalReasons });
      setMessage(`${response.approvalDecision} completed. External ERP write was not executed.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Quote approval action failed.");
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

  return (
    <div className="stack">
      <section className="panel">
        <h2>RFQ to Draft Quote</h2>
        <p className="risk-note">Demo path: Steppe Logistics requests out-of-stock OE brake pads. Operant validates the draft, shows substitute/approval context, and keeps externalExecution=DISABLED.</p>
        <form className="upload-form" onSubmit={submit}>
          <label><span>Customer external ref</span><input name="customerExternalRef" defaultValue="CUST-001" /></label>
          <label><span>SKU or alias</span><input name="rawSkuOrAlias" defaultValue="PAD-OE-04465" /></label>
          <label><span>Description</span><input name="description" defaultValue="Original brake pads for Toyota Camry 2018" /></label>
          <label><span>Quantity</span><input name="quantity" type="number" min="1" defaultValue="2" /></label>
          <label><span>UOM</span><input name="uom" defaultValue="EA" /></label>
          <label><span>Location</span><input name="requestedLocation" defaultValue="WH-ALM" /></label>
          <label><span>Discount percent</span><input name="requestedDiscountPercent" type="number" min="0" step="0.01" defaultValue="0" /></label>
          <button className="button" disabled={loading} type="submit">{loading ? "Submitting..." : "Create Draft Quote"}</button>
        </form>
        {message ? <p className={result ? "form-message done" : "form-message error"}>{message}</p> : null}
      </section>

      {result ? (
        <>
          <section className="panel">
            <h2>Quote</h2>
            <p>Quote: {result.draftQuoteId}</p>
            <p>Status: {result.status}</p>
            <p>Customer: {result.resolvedCustomer?.displayName ?? "Unresolved"}</p>
            <p>Approval: {result.approvalRequired ? result.approvalReasons.join(", ") : "Not required"}</p>
            <p>External ERP write: disabled / not executed</p>
          </section>
          <section className="panel">
            <h2>Approval</h2>
            <p>Status: {approvalState?.status ?? result.status}</p>
            <p>Approval required: {(approvalState?.approvalRequired ?? result.approvalRequired) ? "Yes" : "No"}</p>
            <p>Approval reasons: {(approvalState?.approvalReasons.length ? approvalState.approvalReasons : result.approvalReasons).join(", ") || "None"}</p>
            <p>Blocking issues: {(approvalState?.blockingIssues ?? result.validationIssues.filter((issue) => issue.blocking)).map((issue) => issue.issueCode).join(", ") || "None"}</p>
            <label><span>Reason/comment</span><input aria-label="Approval decision reason" value={decisionReason} onChange={(event) => setDecisionReason(event.target.value)} placeholder="Required for reject or request changes" /></label>
            <div className="action-row">
              <button className="button" disabled={loading || Boolean((approvalState?.blockingIssues ?? result.validationIssues).some((issue) => issue.blocking)) || result.status === "CONVERTED_TO_INTERNAL_ORDER"} type="button" onClick={() => runApprovalAction("approve")}>Approve</button>
              <button className="button secondary-button" disabled={loading || !decisionReason.trim() || result.status === "CONVERTED_TO_INTERNAL_ORDER"} type="button" onClick={() => runApprovalAction("reject")}>Reject</button>
              <button className="button secondary-button" disabled={loading || !decisionReason.trim() || result.status === "CONVERTED_TO_INTERNAL_ORDER"} type="button" onClick={() => runApprovalAction("changes")}>Request changes</button>
              {result.status === "APPROVED" ? <button className="button" disabled={loading} type="button" onClick={() => runApprovalAction("convert")}>Convert to internal order</button> : null}
            </div>
            {approvalResult ? (
              <div className="result-panel">
                <p>Decision: {approvalResult.approvalDecision}</p>
                <p>Previous status: {approvalResult.previousStatus}</p>
                <p>New status: {approvalResult.newStatus}</p>
                <p>Internal draft order boundary: {approvalResult.internalDraftOrderId ?? "Not created"}</p>
                <p>ChangeRequest: {approvalResult.changeRequestId ?? "Not created"}</p>
                <p>External execution: {approvalResult.externalExecutionEnabled ? "Enabled" : "Disabled (no external write)"}</p>
              </div>
            ) : null}
          </section>
          <section className="panel table-panel">
            <h2>Lines</h2>
            <table className="data-table"><thead><tr><th>Item</th><th>Resolved</th><th>Qty</th><th>Price</th><th>Margin</th><th>Status</th></tr></thead><tbody>
              {result.lines.map((line) => <tr key={line.id}><td>{line.rawSkuOrAlias}</td><td>{line.productName ?? "Unresolved"}</td><td>{line.quantity} {line.uom}</td><td>{line.unitPrice ?? "n/a"}</td><td>{line.marginPercent ?? "n/a"}</td><td>{line.validationStatus}</td></tr>)}
            </tbody></table>
          </section>
          <section className="panel table-panel">
            <h2>Validation Issues</h2>
            <table className="data-table"><thead><tr><th>Code</th><th>Severity</th><th>Blocking</th><th>Message</th></tr></thead><tbody>
              {result.validationIssues.length ? result.validationIssues.map((issue) => <tr key={issue.id}><td>{issue.issueCode}</td><td>{issue.severity}</td><td>{issue.blocking ? "Yes" : "No"}</td><td>{issue.message}</td></tr>) : <tr><td colSpan={4}>No validation issues.</td></tr>}
            </tbody></table>
          </section>
          <section className="panel table-panel">
            <h2>Substitutes</h2>
            <table className="data-table"><thead><tr><th>SKU</th><th>Risk</th><th>Stock</th><th>Approval</th></tr></thead><tbody>
              {result.substituteCandidates.length ? result.substituteCandidates.map((candidate) => <tr key={`${candidate.lineId}-${candidate.productId}`}><td>{candidate.sku}</td><td>{candidate.riskLevel}</td><td>{candidate.stockStatus}</td><td>{candidate.requiresApproval || candidate.blocked ? "Required" : "Not required"}</td></tr>) : <tr><td colSpan={4}>No substitute candidates.</td></tr>}
            </tbody></table>
          </section>
        </>
      ) : null}
    </div>
  );
}
