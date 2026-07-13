"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

import {
  assembleQuoteDraft,
  correctQuoteReviewLine,
  escalateQuoteReviewIssue,
  getQuoteReviewDetail,
  getQuoteReviewQueue,
  QuoteDraftSummary,
  QuoteReviewDetail,
  QuoteReviewCommandResult,
  QuoteReviewQueueRow,
  rejectQuoteReviewSubstitute,
  resolveQuoteReviewIssue,
  selectQuoteReviewSubstitute
} from "@/lib/quote-review-api";
import { generateIdempotencyKey } from "@/lib/security-idempotency";
import { boundedUiErrorMessage } from "@/lib/ui-error";
import {
  mapOperatorActionError,
  OperatorActionResult,
  useOperatorAction
} from "@/lib/operator-action-runtime";

type LoadKind = "loading" | "ready" | "forbidden" | "not_found" | "error";
type OperatorActionRuntime<T> = {
  execute: (action: () => Promise<OperatorActionResult<T>>) => Promise<OperatorActionResult<T>>;
};

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
  const [messageKind, setMessageKind] = useState<"done" | "error">("done");
  const [draftSummary, setDraftSummary] = useState<QuoteDraftSummary | null>(null);
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

  // OP-CAP-35: Quote Review mutations use the shared operator-action runtime.
  // Pending/disabled state, duplicate-click guard, and safe error mapping are
  // provided by useOperatorAction. Idempotency key generation keeps the existing
  // UUID-per-action pattern via generateIdempotencyKey (random, cached per
  // actionKey) — not weakening it to the deterministic runtime helper.
  const { execute, pending, disabled } = useOperatorAction<QuoteReviewCommandResult>();
  const operatorAction = { execute, pending, disabled };
  const draftAssemblyAction = useOperatorAction<QuoteDraftSummary>();
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

  // OP-CAP-35: wraps an API call with the shared useOperatorAction execute,
  // idempotency key caching (UUID-per-actionKey, generateIdempotencyKey),
  // and status-specific safe error mapping via mapOperatorActionError.
  async function doAction<T>(
    actionKey: string,
    operation: (idempotencyKey: string) => Promise<T>,
    doneMessage: string,
    actionRuntime?: OperatorActionRuntime<T>
  ) {
    let idempotencyKey = mutationKeysRef.current.get(actionKey);
    if (!idempotencyKey) {
      try {
        idempotencyKey = generateIdempotencyKey();
        mutationKeysRef.current.set(actionKey, idempotencyKey);
      } catch {
        setMessageKind("error");
        setMessage("Secure idempotency key generation failed.");
        return;
      }
    }

    const runOperation = async (): Promise<OperatorActionResult<T>> => {
      try {
        const data = await operation(idempotencyKey);
        return { ok: true as const, data, safeMessage: doneMessage };
      } catch (error) {
        const err = error as Error & { status?: number };
        const { errorCode, safeMessage } = mapOperatorActionError(
          err.status ?? 500,
          boundedUiErrorMessage(error, "The action could not be completed. Please try again or contact support.")
        );
        return { ok: false as const, errorCode, safeMessage };
      }
    };

    if (!actionRuntime) {
      const result = await execute(runOperation as () => Promise<OperatorActionResult<QuoteReviewCommandResult>>);

      // Clear the cached idempotency key only after a successful completion so the
      // next intentional click on the same actionKey gets a fresh key (avoids the
      // backend treating it as a duplicate/replay). Failed attempts and in-flight
      // duplicate guards keep the key so a genuine retry stays idempotency-safe.
      if (result.ok) {
        mutationKeysRef.current.delete(actionKey);
      }
      await handleActionResult(result);
      return;
    }

    const result = await actionRuntime.execute(runOperation);
    if (result.ok) {
      mutationKeysRef.current.delete(actionKey);
    }
    await handleActionResult(result);
  }

  async function handleActionResult<T>(result: OperatorActionResult<T>) {
    if (result.ok) {
      setMessageKind("done");
      setMessage(result.safeMessage);
      try {
        applyResult(await getQuoteReviewDetail(quoteId));
      } catch {
        setMessageKind("error");
        setMessage("Action succeeded but review could not be reloaded. Refresh to see the latest state.");
      }
    } else {
      setMessageKind("error");
      setMessage(result.safeMessage);
    }
  }

  // OP-CAP-36: assemble a safe draft quote candidate. Sends business intent only.
  // The backend owns status/totals/risk/approval/stock; the returned summary is a
  // backend-calculated snapshot rendered in the draft summary panel. The detail is
  // reloaded by the shared onSuccess handler so the workspace reflects new state.
  async function assembleDraft() {
    await doAction(
      "assemble-draft",
      async (idempotencyKey) => {
        const summary = await assembleQuoteDraft(quoteId, { reasonCode: reason, note, idempotencyKey });
        setDraftSummary(summary);
        return summary;
      },
      "Draft quote assembled. Backend-owned draft summary is shown below.",
      draftAssemblyAction
    );
  }

  const hasOpenBlockingIssue = !!detail && detail.validationIssues.some((issue) => issue.blocking && issue.status === "OPEN");

  return (
    <div className="stack">
      <section className="panel">
        <h2>Quote Review Detail</h2>
        <form className="upload-form" onSubmit={load}>
          <label><span>Reason code</span><input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
          <label><span>Operator note</span><input value={note} onChange={(event) => setNote(event.target.value)} /></label>
          <button className="button" type="submit">Reload Review</button>
        </form>
        {message ? <p className={messageKind === "error" ? "form-message error" : "form-message done"}>{message}</p> : null}
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
                      <button className="button secondary-button" disabled={issue.status !== "OPEN" || disabled} onClick={() => doAction(`resolve-issue-${issue.id}`, (idempotencyKey) => resolveQuoteReviewIssue(quoteId, issue.id, { reasonCode: reason, note, idempotencyKey }), "Issue resolution persisted and quote revalidated.")}>Resolve</button>
                      <button className="button secondary-button" disabled={issue.status !== "OPEN" || disabled} onClick={() => doAction(`escalate-issue-${issue.id}`, (idempotencyKey) => escalateQuoteReviewIssue(quoteId, issue.id, { reasonCode: reason, note, idempotencyKey }), "Issue escalated to approval/review path.")}>Escalate</button>
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
                    <button className="button secondary-button" disabled={disabled} onClick={() => doAction(`line-qty-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { quantity: Number(line.quantity) > 0 ? Number(line.quantity) : 1, reasonCode: reason, note, idempotencyKey }), "Line quantity correction persisted and revalidated.")}>Fix qty</button>
                    <button className="button secondary-button" disabled={disabled} onClick={() => doAction(`line-uom-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { uom: "EA", reasonCode: reason, note, idempotencyKey }), "Line UOM correction persisted and revalidated.")}>Set EA</button>
                    <button className="button secondary-button" disabled={disabled} onClick={() => doAction(`line-follow-up-${line.id}`, (idempotencyKey) => correctQuoteReviewLine(quoteId, line.id, { manualFollowUp: true, reasonCode: "MANUAL_FOLLOW_UP", note, idempotencyKey }), "Line marked for manual follow-up.")}>Follow-up</button>
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
                    <button className="button secondary-button" disabled={!candidate.lineId || candidate.blocked || disabled} onClick={() => doAction(`substitute-select-${candidate.lineId}-${candidate.productId}`, (idempotencyKey) => selectQuoteReviewSubstitute(quoteId, candidate.lineId!, { substituteProductId: candidate.productId, reasonCode: reason, note, idempotencyKey }), candidate.requiresApproval ? "Substitute selected and routed to approval." : "Substitute selection persisted and revalidated.")}>Select</button>
                    <button className="button secondary-button" disabled={!candidate.lineId || disabled} onClick={() => doAction(`substitute-reject-${candidate.lineId}-${candidate.productId}`, (idempotencyKey) => rejectQuoteReviewSubstitute(quoteId, candidate.lineId!, { substituteProductId: candidate.productId, reasonCode: reason, note, idempotencyKey }), "Substitute rejection persisted.")}>Reject</button>
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

          <section className="panel">
            <h2>Assemble Draft Quote</h2>
            <p>Assemble a draft quote candidate once review data is valid. The backend recalculates totals, risk, approval requirement and draft status — this action sends operator intent only and never any totals, status, or approval state.</p>
            {hasOpenBlockingIssue
              ? <p className="form-message error">Resolve all open blocking validation issues before assembling the draft quote.</p>
              : null}
            <button className="button" disabled={hasOpenBlockingIssue || disabled} onClick={assembleDraft}>{pending ? "Working..." : "Assemble Draft Quote"}</button>
          </section>

          {draftSummary ? (
            <section className="panel">
              <h2>Draft Quote Summary</h2>
              <p>Draft status: {humanize(draftSummary.draftStatus)}</p>
              <p>Customer: {draftSummary.customer.displayName ?? draftSummary.customer.resolutionStatus}</p>
              <p>Lines: {draftSummary.lineCount}</p>
              <p>Subtotal: {draftSummary.subtotalAmount ?? "n/a"} {draftSummary.currency ?? ""}</p>
              <p>Total: {draftSummary.totalAmount ?? "n/a"} {draftSummary.currency ?? ""}</p>
              <p>Margin: {draftSummary.marginPercent ?? "n/a"}</p>
              <p>Risk level: {humanize(draftSummary.riskLevel)}</p>
              <p>Approval required: {draftSummary.approvalRequired ? "Yes" : "No"}</p>
              <p>Margin status: {humanize(draftSummary.marginStatus)}</p>
              <p>Unresolved blocking issues: {draftSummary.unresolvedBlockingIssueCount}</p>
              <p>Warnings: {draftSummary.warningCount} (stock {draftSummary.stockWarningCount})</p>
              <p>Next action: {humanize(draftSummary.nextAction)}</p>
              <p>{draftSummary.operatorMessage}</p>
              <p>External execution: {draftSummary.externalExecution}</p>
              {draftSummary.externalSyncCandidateStatus === "PREPARED" ? (
                <p>External sync candidate prepared for review (no external execution).</p>
              ) : draftSummary.externalSyncCandidateStatus === "PENDING_INTERNAL_APPROVAL" ? (
                <p>External sync candidate pending internal approval.</p>
              ) : null}
            </section>
          ) : null}

          <section className="panel table-panel">
            <h2>Audit Timeline</h2>
            <table className="data-table">
              <thead><tr><th>When</th><th>Action</th></tr></thead>
              <tbody>{detail.auditTimeline.length ? detail.auditTimeline.map((event) => <tr key={`${event.occurredAt}-${event.action}`}><td>{event.occurredAt}</td><td>{humanize(event.action)}</td></tr>) : <tr><td colSpan={2}>No mutation audit events yet. Create or review a draft quote to populate the controlled audit trail.</td></tr>}</tbody>
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
