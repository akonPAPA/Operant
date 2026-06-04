"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";

import {
  correctQuoteReviewLine,
  escalateQuoteReviewIssue,
  getQuoteReviewDetail,
  getQuoteReviewQueue,
  QuoteReviewDetail,
  QuoteReviewQueueRow,
  rejectQuoteReviewSubstitute,
  resolveQuoteReviewIssue,
  selectQuoteReviewSubstitute
} from "@/lib/quote-review-api";

const demoTenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "11111111-1111-4111-8111-111111111111";

export function QuoteReviewQueue() {
  const [tenantId, setTenantId] = useState(demoTenantId);
  const [rows, setRows] = useState<QuoteReviewQueueRow[]>([]);
  const [message, setMessage] = useState("");

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setMessage("");
    try {
      setRows(await getQuoteReviewQueue(tenantId));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Review queue failed.");
    }
  }, [tenantId]);

  useEffect(() => {
    let cancelled = false;
    getQuoteReviewQueue(tenantId)
      .then((nextRows) => {
        if (!cancelled) setRows(nextRows);
      })
      .catch((error) => {
        if (!cancelled) setMessage(error instanceof Error ? error.message : "Review queue failed.");
      });
    return () => {
      cancelled = true;
    };
  }, [tenantId]);

  return (
    <div className="stack">
      <section className="panel">
        <h2>Review Queue</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
          <button className="button" type="submit">Refresh Queue</button>
        </form>
        {message ? <p className="form-message error">{message}</p> : null}
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Quote</th><th>Status</th><th>Customer</th><th>Issues</th><th>Severity</th><th>Source</th><th>Next action</th><th>Open</th></tr></thead>
          <tbody>
            {rows.length ? rows.map((row) => (
              <QuoteReviewQueueTableRow key={row.quoteId} row={row} tenantId={tenantId} />
            )) : <tr><td colSpan={8}>No tenant-owned quote reviews returned by backend.</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}

function QuoteReviewQueueTableRow({ row, tenantId }: { row: QuoteReviewQueueRow; tenantId: string }) {
  const reviewHref = buildQuoteReviewHref(row.quoteId, tenantId);
  return (
    <tr>
      <td>Quote {shortId(row.quoteId)}<br /><span className="muted-copy">{row.conversionAttemptId ? `conversion ${shortId(row.conversionAttemptId)}` : "direct quote review"}</span></td>
      <td><span className={`status-pill ${row.status.includes("REVIEW") ? "warning" : ""}`}>{humanize(row.status)}</span></td>
      <td>{row.customer.displayName ?? row.customer.resolutionStatus}</td>
      <td>{row.validationIssueCount}</td>
      <td>{row.highestSeverity}</td>
      <td>{row.sourceType ?? "RFQ"} {row.sourceChannel ? `/${row.sourceChannel}` : ""}</td>
      <td>{humanize(row.nextRequiredAction)}</td>
      <td>{reviewHref ? <a className="button secondary-button" href={reviewHref}>Open Review</a> : <span className="muted-copy">Unavailable</span>}</td>
    </tr>
  );
}

export function QuoteReviewDetailWorkspace({ quoteId, initialTenantId = demoTenantId }: { quoteId: string; initialTenantId?: string }) {
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [detail, setDetail] = useState<QuoteReviewDetail | null>(null);
  const [reason, setReason] = useState("OPERATOR_REVIEW");
  const [note, setNote] = useState("");
  const [message, setMessage] = useState("");

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setMessage("");
    try {
      setDetail(await getQuoteReviewDetail(tenantId, quoteId));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Review detail failed.");
    }
  }, [quoteId, tenantId]);

  useEffect(() => {
    let cancelled = false;
    getQuoteReviewDetail(tenantId, quoteId)
      .then((nextDetail) => {
        if (!cancelled) setDetail(nextDetail);
      })
      .catch((error) => {
        if (!cancelled) setMessage(error instanceof Error ? error.message : "Review detail failed.");
      });
    return () => {
      cancelled = true;
    };
  }, [quoteId, tenantId]);

  async function mutate(operation: () => Promise<unknown>, done: string) {
    setMessage("");
    try {
      await operation();
      setDetail(await getQuoteReviewDetail(tenantId, quoteId));
      setMessage(done);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Review command failed.");
    }
  }

  return (
    <div className="stack">
      <section className="panel">
        <h2>Quote Review Detail</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Tenant ID</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
          <label><span>Reason code</span><input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
          <label><span>Operator note</span><input value={note} onChange={(event) => setNote(event.target.value)} /></label>
          <button className="button" type="submit">Reload Review</button>
        </form>
        {message ? <p className={message.includes("failed") || message.includes("Core API") ? "form-message error" : "form-message done"}>{message}</p> : null}
      </section>

      {detail ? (
        <>
          <section className="panel">
            <h2>Review Status</h2>
            <p>Quote: {detail.header.quoteNumber}</p>
            <p>Status: {humanize(detail.status)}</p>
            <p>Customer: {detail.header.customer.displayName ?? detail.header.customer.resolutionStatus}</p>
            <p>Review-required reasons: {formatList(detail.reviewRequiredReasons)}</p>
            <p>Approval required: {detail.pricingSummary.approvalRequired || detail.approvalRequirements.some((approval) => approval.status === "OPEN") ? "Yes" : "No"}</p>
            <p>External ERP write: disabled / not executed</p>
            <p>externalExecution=DISABLED</p>
          </section>

          <section className="panel table-panel">
            <h2>Source Evidence</h2>
            <p>Source context: {detail.sourceContext ? `${humanize(detail.sourceContext.sourceType)} ${detail.sourceContext.sourceChannel ?? ""}` : "RFQ/manual source or source link unavailable"}</p>
            <p>Conversion attempt: {detail.conversionAttempt ? `${shortId(detail.conversionAttempt.id)} / ${humanize(detail.conversionAttempt.status)}` : "None"}</p>
            <table className="data-table">
              <thead><tr><th>Line</th><th>SKU/Text</th><th>Qty</th><th>Status</th></tr></thead>
              <tbody>{detail.sourceLines.length ? detail.sourceLines.map((line) => <tr key={`${line.lineNumber}-${line.sourceLineItemId ?? line.rawSkuOrAlias}`}><td>{line.lineNumber}</td><td>{line.rawSkuOrAlias ?? line.description}</td><td>{line.quantity ?? "n/a"} {line.uom ?? ""}</td><td>{line.status ?? "SOURCE"}</td></tr>) : <tr><td colSpan={4}>No extracted source lines available.</td></tr>}</tbody>
            </table>
          </section>

          <section className="panel table-panel">
            <h2>Validation Issue Panel</h2>
            <table className="data-table">
              <thead><tr><th>Reason code</th><th>Severity</th><th>Status</th><th>Message</th><th>Decision</th></tr></thead>
              <tbody>
                {detail.validationIssues.length ? detail.validationIssues.map((issue) => (
                  <tr key={issue.id}>
                    <td>{humanize(issue.issueCode)}</td>
                    <td>{issue.severity}</td>
                    <td>{issue.status}</td>
                    <td>{issue.message}</td>
                    <td className="action-row">
                      <button className="button secondary-button" disabled={issue.status !== "OPEN"} onClick={() => mutate(() => resolveQuoteReviewIssue(quoteId, issue.id, { tenantId, reasonCode: reason, note }), "Issue resolution persisted and quote revalidated.")}>Resolve</button>
                      <button className="button secondary-button" disabled={issue.status !== "OPEN"} onClick={() => mutate(() => escalateQuoteReviewIssue(quoteId, issue.id, { tenantId, reasonCode: reason, note }), "Issue escalated to approval/review path.")}>Escalate</button>
                    </td>
                  </tr>
                )) : <tr><td colSpan={5}>No validation issues.</td></tr>}
              </tbody>
            </table>
          </section>

          <section className="panel table-panel">
            <h2>Suggested Fix Panel</h2>
            <table className="data-table">
              <thead><tr><th>Line</th><th>Product</th><th>Qty</th><th>UOM</th><th>Status</th><th>Correction</th></tr></thead>
              <tbody>{detail.draftQuoteLines.map((line) => (
                <tr key={line.id}>
                  <td>{line.lineNumber}</td>
                  <td>{line.productName ?? line.rawSkuOrAlias ?? "Unresolved"}</td>
                  <td>{line.quantity}</td>
                  <td>{line.uom}</td>
                  <td>{humanize(line.validationStatus)}</td>
                  <td className="action-row">
                    <button className="button secondary-button" onClick={() => mutate(() => correctQuoteReviewLine(quoteId, line.id, { tenantId, quantity: Number(line.quantity) > 0 ? Number(line.quantity) : 1, reasonCode: reason, note }), "Line quantity correction persisted and revalidated.")}>Fix qty</button>
                    <button className="button secondary-button" onClick={() => mutate(() => correctQuoteReviewLine(quoteId, line.id, { tenantId, uom: "EA", reasonCode: reason, note }), "Line UOM correction persisted and revalidated.")}>Set EA</button>
                    <button className="button secondary-button" onClick={() => mutate(() => correctQuoteReviewLine(quoteId, line.id, { tenantId, manualFollowUp: true, reasonCode: "MANUAL_FOLLOW_UP", note }), "Line marked for manual follow-up.")}>Follow-up</button>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          </section>

          <section className="panel table-panel">
            <h2>Substitute Candidates Panel</h2>
            <table className="data-table">
              <thead><tr><th>SKU</th><th>Risk</th><th>Stock</th><th>Reason</th><th>Decision</th></tr></thead>
              <tbody>{detail.proposedSubstitutes.length ? detail.proposedSubstitutes.map((candidate) => (
                <tr key={`${candidate.lineId}-${candidate.productId}`}>
                  <td>{candidate.sku}</td>
                  <td>{humanize(candidate.riskLevel)}{candidate.blocked ? " / BLOCKED" : ""}</td>
                  <td>{candidate.stockStatus}</td>
                  <td>{humanize(candidate.reasonCode)}</td>
                  <td className="action-row">
                    <button className="button secondary-button" disabled={!candidate.lineId || candidate.blocked} onClick={() => mutate(() => selectQuoteReviewSubstitute(quoteId, candidate.lineId!, { tenantId, substituteProductId: candidate.productId, reasonCode: reason, note }), candidate.requiresApproval ? "Substitute selected and routed to approval." : "Substitute selection persisted and revalidated.")}>Select</button>
                    <button className="button secondary-button" disabled={!candidate.lineId} onClick={() => mutate(() => rejectQuoteReviewSubstitute(quoteId, candidate.lineId!, { tenantId, substituteProductId: candidate.productId, reasonCode: reason, note }), "Substitute rejection persisted.")}>Reject</button>
                  </td>
                </tr>
              )) : <tr><td colSpan={5}>Substitute selection is enabled only when backend-compatible candidates exist.</td></tr>}</tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Approval Status</h2>
            <p>Margin risk: {detail.pricingSummary.marginRisk ? "Yes" : "No"}</p>
            <p>Discount risk: {detail.pricingSummary.discountRisk ? "Yes" : "No"}</p>
            <p>Open requirements: {formatList(detail.approvalRequirements.filter((approval) => approval.status === "OPEN").map((approval) => approval.reasonCode))}</p>
            <p>External execution: DISABLED until an approved connector flow exists.</p>
          </section>

          <section className="panel table-panel">
            <h2>Audit Timeline</h2>
            <table className="data-table">
              <thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Metadata</th></tr></thead>
              <tbody>{detail.auditTimeline.length ? detail.auditTimeline.map((event) => <tr key={event.id}><td>{event.occurredAt}</td><td>{humanize(event.action)}</td><td>{event.actorId ? shortId(event.actorId) : "system"}</td><td>{summarizeMetadata(event.metadata)}</td></tr>) : <tr><td colSpan={4}>No mutation audit events yet. Create or review a draft quote to populate the controlled audit trail.</td></tr>}</tbody>
            </table>
          </section>
        </>
      ) : null}
    </div>
  );
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function buildQuoteReviewHref(quoteId: string, tenantId: string) {
  if (!UUID_RE.test(quoteId)) return null;
  const params = new URLSearchParams({ tenantId });
  return `/quote-review/${encodeURIComponent(quoteId)}?${params.toString()}`;
}

function shortId(value?: string | null) {
  if (!value) return "n/a";
  return value.length > 8 ? value.slice(0, 8) : value;
}

function humanize(value?: string | null) {
  if (!value) return "n/a";
  return value.replaceAll("_", " ").replaceAll(".", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatList(values: string[]) {
  return values.length ? values.map(humanize).join(", ") : "None";
}

function summarizeMetadata(metadata: string) {
  try {
    const parsed = JSON.parse(metadata) as Record<string, unknown>;
    const parts = ["reason", "reasonCode", "decision", "externalExecution", "newStatus", "previousStatus"]
      .map((key) => parsed[key] ? `${humanize(key)}: ${String(parsed[key])}` : "")
      .filter(Boolean);
    return parts.length ? parts.join(" | ") : metadata;
  } catch {
    return metadata;
  }
}
