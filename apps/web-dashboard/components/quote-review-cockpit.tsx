"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

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
import { generateIdempotencyKey } from "@/lib/security-idempotency";

type LoadKind = "loading" | "ready" | "forbidden" | "not_found" | "error";

export function QuoteReviewQueue() {
  const [rows, setRows] = useState<QuoteReviewQueueRow[]>([]);
  const [loadState, setLoadState] = useState<LoadKind>("loading");
  const [message, setMessage] = useState("");

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setLoadState("loading");
    setMessage("");
    const result = await getQuoteReviewQueue();
    if (result.ok) {
      setRows(result.data ?? []);
      setLoadState("ready");
    } else {
      setRows([]);
      setLoadState(result.kind === "forbidden" || result.kind === "not_found" ? result.kind : "error");
      setMessage(result.message);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await getQuoteReviewQueue();
      if (cancelled) return;
      if (result.ok) {
        setRows(result.data ?? []);
        setLoadState("ready");
      } else {
        setRows([]);
        setLoadState(result.kind === "forbidden" || result.kind === "not_found" ? result.kind : "error");
        setMessage(result.message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="stack">
      <section className="panel">
        <h2>Review Queue</h2>
        <form className="upload-form" onSubmit={load}>
          <button className="button" type="submit">Refresh Queue</button>
        </form>
        {message ? <p className="form-message error">{message}</p> : null}
      </section>
      <section className="panel table-panel">
        <table className="data-table">
          <thead><tr><th>Quote</th><th>Status</th><th>Customer</th><th>Issues</th><th>Severity</th><th>Source</th><th>Next action</th><th>Open</th></tr></thead>
          <tbody>
            {loadState === "loading" ? <tr><td colSpan={8}>Loading review queue...</td></tr> : null}
            {loadState === "forbidden" || loadState === "not_found" || loadState === "error"
              ? <tr><td colSpan={8}>{message}</td></tr>
              : null}
            {loadState === "ready" && rows.length === 0 ? <tr><td colSpan={8}>No reviews found.</td></tr> : null}
            {loadState === "ready" && rows.map((row) => (
              <QuoteReviewQueueTableRow key={row.quoteId} row={row} />
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

function QuoteReviewQueueTableRow({ row }: { row: QuoteReviewQueueRow }) {
  const reviewHref = buildQuoteReviewHref(row.quoteId);
  return (
    <tr>
      <td>Quote {shortId(row.quoteId)}<br /><span className="muted-copy">{row.sourceType ? humanize(row.sourceType) : "direct quote review"}</span></td>
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

export function QuoteReviewDetailWorkspace({ quoteId }: { quoteId: string }) {
  const [detail, setDetail] = useState<QuoteReviewDetail | null>(null);
  const [loadState, setLoadState] = useState<LoadKind>("loading");
  const [reason, setReason] = useState("OPERATOR_REVIEW");
  const [note, setNote] = useState("");
  const [message, setMessage] = useState("");
  const [mutationInFlight, setMutationInFlight] = useState(false);
  const mutationInFlightRef = useRef(false);
  const mutationKeysRef = useRef<Map<string, string>>(new Map());

  const applyResult = useCallback((result: Awaited<ReturnType<typeof getQuoteReviewDetail>>) => {
    if (result.ok) {
      setDetail(result.data ?? null);
      setLoadState(result.data ? "ready" : "not_found");
    } else {
      setDetail(null);
      setLoadState(result.kind === "forbidden" || result.kind === "not_found" ? result.kind : "error");
      setMessage(result.message);
    }
  }, []);

  const load = useCallback(async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    setLoadState("loading");
    setMessage("");
    applyResult(await getQuoteReviewDetail(quoteId));
  }, [quoteId, applyResult]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await getQuoteReviewDetail(quoteId);
      if (!cancelled) applyResult(result);
    })();
    return () => {
      cancelled = true;
    };
  }, [quoteId, applyResult]);

  async function mutate(actionKey: string, operation: (idempotencyKey: string) => Promise<unknown>, done: string) {
    if (mutationInFlightRef.current) return;
    let idempotencyKey = mutationKeysRef.current.get(actionKey);
    if (!idempotencyKey) {
      try {
        idempotencyKey = generateIdempotencyKey();
        mutationKeysRef.current.set(actionKey, idempotencyKey);
      } catch (error) {
        setMessage(error instanceof Error ? error.message : "Secure idempotency key generation failed.");
        return;
      }
    }
    mutationInFlightRef.current = true;
    setMutationInFlight(true);
    setMessage("");
    try {
      await operation(idempotencyKey);
      applyResult(await getQuoteReviewDetail(quoteId));
      mutationKeysRef.current.delete(actionKey);
      setMessage(done);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Review command failed.");
    }
    mutationInFlightRef.current = false;
    setMutationInFlight(false);
  }

  return (
    <div className="stack">
      <section className="panel">
        <h2>Quote Review Detail</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Reason code</span><input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
          <label><span>Operator note</span><input value={note} onChange={(event) => setNote(event.target.value)} /></label>
          <button className="button" type="submit">Reload Review</button>
        </form>
        {message ? <p className={message.includes("failed") || message.includes("Could not") || message.includes("not found") || message.includes("do not have access") ? "form-message error" : "form-message done"}>{message}</p> : null}
      </section>

      {loadState === "loading" ? <section className="panel"><h2>Loading</h2><p>Loading quote review detail...</p></section> : null}
      {loadState === "forbidden" || loadState === "not_found" || loadState === "error"
        ? <section className="panel"><h2>Review unavailable</h2><p className="form-message error">{message}</p></section>
        : null}

      {loadState === "ready" && detail ? (
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
              <tbody>{detail.sourceLines.length ? detail.sourceLines.map((line) => <tr key={`${line.lineNumber}-${line.rawSkuOrAlias ?? line.description ?? "source-line"}`}><td>{line.lineNumber}</td><td>{line.rawSkuOrAlias ?? line.description}</td><td>{line.quantity ?? "n/a"} {line.uom ?? ""}</td><td>{line.status ?? "SOURCE"}</td></tr>) : <tr><td colSpan={4}>No extracted source lines available.</td></tr>}</tbody>
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
                      <button className="button secondary-button" disabled={issue.status !== "OPEN" || mutationInFlight} onClick={() => mutate(`resolve-issue-${issue.id}`, (idempotencyKey) => resolveQuoteReviewIssue(quoteId, issue.id, { reasonCode: reason, note, idempotencyKey }), "Issue resolution persisted and quote revalidated.")}>Resolve</button>
                      <button className="button secondary-button" disabled={issue.status !== "OPEN" || mutationInFlight} onClick={() => mutate(`escalate-issue-${issue.id}`, (idempotencyKey) => escalateQuoteReviewIssue(quoteId, issue.id, { reasonCode: reason, note, idempotencyKey }), "Issue escalated to approval/review path.")}>Escalate</button>
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
                    <button className="button secondary-button" disabled={mutationInFlight} onClick={() => mutate(`line-qty-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { quantity: Number(line.quantity) > 0 ? Number(line.quantity) : 1, reasonCode: reason, note, idempotencyKey }), "Line quantity correction persisted and revalidated.")}>Fix qty</button>
                    <button className="button secondary-button" disabled={mutationInFlight} onClick={() => mutate(`line-uom-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { uom: "EA", reasonCode: reason, note, idempotencyKey }), "Line UOM correction persisted and revalidated.")}>Set EA</button>
                    <button className="button secondary-button" disabled={mutationInFlight} onClick={() => mutate(`line-follow-up-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { manualFollowUp: true, reasonCode: "MANUAL_FOLLOW_UP", note, idempotencyKey }), "Line marked for manual follow-up.")}>Follow-up</button>
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
                    <button className="button secondary-button" disabled={!candidate.lineId || candidate.blocked || mutationInFlight} onClick={() => mutate(`substitute-select-${candidate.lineId}-${candidate.productId}`, (idempotencyKey) => selectQuoteReviewSubstitute(quoteId, candidate.lineId!, { substituteProductId: candidate.productId, reasonCode: reason, note, idempotencyKey }), candidate.requiresApproval ? "Substitute selected and routed to approval." : "Substitute selection persisted and revalidated.")}>Select</button>
                    <button className="button secondary-button" disabled={!candidate.lineId || mutationInFlight} onClick={() => mutate(`substitute-reject-${candidate.lineId}-${candidate.productId}`, (idempotencyKey) => rejectQuoteReviewSubstitute(quoteId, candidate.lineId!, { substituteProductId: candidate.productId, reasonCode: reason, note, idempotencyKey }), "Substitute rejection persisted.")}>Reject</button>
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
              <tbody>{detail.auditTimeline.length ? detail.auditTimeline.map((event) => <tr key={`${event.occurredAt}-${event.action}`}><td>{event.occurredAt}</td><td>{humanize(event.action)}</td><td>{auditActorLabel(event.metadata)}</td><td>{summarizeMetadata(event.metadata)}</td></tr>) : <tr><td colSpan={4}>No mutation audit events yet. Create or review a draft quote to populate the controlled audit trail.</td></tr>}</tbody>
            </table>
          </section>
        </>
      ) : null}
    </div>
  );
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function buildQuoteReviewHref(quoteId: string) {
  if (!UUID_RE.test(quoteId)) return null;
  return `/quote-review/${encodeURIComponent(quoteId)}`;
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

function auditActorLabel(metadata: string) {
  try {
    const parsed = JSON.parse(metadata) as Record<string, unknown>;
    return parsed["actorType"] ? humanize(String(parsed["actorType"])) : "system";
  } catch {
    return "system";
  }
}
