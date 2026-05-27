"use client";

import { FormEvent, useState } from "react";

import { createDraftQuoteFromRfq, QuoteTransactionResponse } from "@/lib/quote-transaction-api";

const demoTenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "11111111-1111-4111-8111-111111111111";

export function QuoteWorkspace() {
  const [result, setResult] = useState<QuoteTransactionResponse | null>(null);
  const [message, setMessage] = useState<string>("");
  const [loading, setLoading] = useState(false);
  const [defaultIdempotencyKey] = useState(() => `quote-workspace-${Date.now()}`);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setMessage("");
    const form = new FormData(event.currentTarget);
    try {
      const response = await createDraftQuoteFromRfq({
        tenantId: String(form.get("tenantId") || demoTenantId),
        actorRole: "OPERATOR",
        customerExternalRef: String(form.get("customerExternalRef") || "DEMO-CUST-001"),
        requestedLocation: String(form.get("requestedLocation") || "ALM-MAIN"),
        requestedDiscountPercent: Number(form.get("requestedDiscountPercent") || 0),
        idempotencyKey: String(form.get("idempotencyKey") || `quote-workspace-${Date.now()}`),
        requestedItems: [{
          rawSkuOrAlias: String(form.get("rawSkuOrAlias") || "TOY-CAM-2018-BPAD-OE"),
          description: String(form.get("description") || "Original brake pads for Toyota Camry 2018"),
          quantity: Number(form.get("quantity") || 2),
          uom: String(form.get("uom") || "EA")
        }]
      });
      setResult(response);
      setMessage("Draft quote created through the backend transaction service.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Quote request failed.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="stack">
      <section className="panel">
        <h2>RFQ to Draft Quote</h2>
        <form className="upload-form" onSubmit={submit}>
          <label><span>Tenant ID</span><input name="tenantId" defaultValue={demoTenantId} /></label>
          <label><span>Customer external ref</span><input name="customerExternalRef" defaultValue="DEMO-CUST-001" /></label>
          <label><span>SKU or alias</span><input name="rawSkuOrAlias" defaultValue="TOY-CAM-2018-BPAD-OE" /></label>
          <label><span>Description</span><input name="description" defaultValue="Original brake pads for Toyota Camry 2018" /></label>
          <label><span>Quantity</span><input name="quantity" type="number" min="1" defaultValue="2" /></label>
          <label><span>UOM</span><input name="uom" defaultValue="EA" /></label>
          <label><span>Location</span><input name="requestedLocation" defaultValue="ALM-MAIN" /></label>
          <label><span>Discount percent</span><input name="requestedDiscountPercent" type="number" min="0" step="0.01" defaultValue="0" /></label>
          <label><span>Idempotency key</span><input name="idempotencyKey" defaultValue={defaultIdempotencyKey} /></label>
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
            <p>Audit correlation: {result.auditCorrelationId}</p>
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
