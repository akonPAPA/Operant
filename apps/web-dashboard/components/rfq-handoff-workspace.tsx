"use client";

import Link from "next/link";
import { useState } from "react";
import { AiWorkSchemaV1View } from "@/components/ai-work-schema-v1-view";
import { RfqCockpitJourney } from "@/components/rfq-cockpit-journey";
import {
  createDraftQuoteFromRfqHandoff,
  decideRfqHandoffDraft,
  dismissRfqHandoff,
  generateRfqHandoffAiSuggestion,
  getRfqHandoff,
  isTerminal,
  listRfqHandoffs,
  markConvertedRfqHandoff,
  startReviewRfqHandoff,
  statusClass,
  statusLabel,
  type RfqHandoffDraftQuote,
  type RfqHandoffDecision,
  type RfqHandoffDecisionResult,
  type RfqHandoff,
  type RfqHandoffStatus
} from "@/lib/rfq-handoff-api";
import type { AiWorkSuggestion } from "@/lib/ai-work-api";

// Exact backend status values — do not rename. "all" is a UI-only convenience filter.
const STATUS_FILTERS = ["PENDING_REVIEW", "IN_REVIEW", "CONVERTED", "DISMISSED", "all"] as const;
type StatusFilter = (typeof STATUS_FILTERS)[number];

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };

function formatTimestamp(ts?: string): string {
  if (!ts) return "n/a";
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return ts;
  }
}

function formatMaybeNumber(value?: number | string | null): string {
  if (value == null || value === "") return "—";
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : "—";
  return value;
}

function formatIssueCodes(value?: string | null): string {
  if (!value) return "";
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) {
      return parsed.filter((item): item is string => typeof item === "string").join(", ");
    }
  } catch {
    // Preserve the backend-provided display value without exposing parser details.
  }
  return value;
}

export function RfqHandoffWorkspace({
  initialHandoffs,
  initialError
}: Readonly<{
  initialHandoffs: RfqHandoff[];
  initialError?: string;
}>) {
  const [handoffs, setHandoffs] = useState<RfqHandoff[]>(initialHandoffs);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("PENDING_REVIEW");
  const [selectedId, setSelectedId] = useState<string | null>(initialHandoffs[0]?.id ?? null);
  const [detail, setDetail] = useState<RfqHandoff | null>(initialHandoffs[0] ?? null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);
  const [isLoadingList, setIsLoadingList] = useState(false);

  // Dismiss / convert input state
  const [dismissReason, setDismissReason] = useState("");
  const [showDismiss, setShowDismiss] = useState(false);
  const [conversionNote, setConversionNote] = useState("");
  const [showConvert, setShowConvert] = useState(false);
  const [aiSuggestion, setAiSuggestion] = useState<AiWorkSuggestion | null>(null);
  const [draftResult, setDraftResult] = useState<RfqHandoffDraftQuote | null>(null);
  const [decisionNote, setDecisionNote] = useState("");
  const [decisionResult, setDecisionResult] = useState<RfqHandoffDecisionResult | null>(null);

  const [action, setAction] = useState<ActionState>({
    status: initialError ? "error" : "idle",
    message: initialError ?? ""
  });
  const [isMutating, setIsMutating] = useState(false);

  const busy = isMutating || isLoadingDetail || isLoadingList;

  async function applyFilter(next: StatusFilter) {
    setStatusFilter(next);
    setIsLoadingList(true);
    setAction({ status: "idle", message: "" });
    const result = await listRfqHandoffs(next === "all" ? undefined : (next as RfqHandoffStatus));
    setIsLoadingList(false);
    if (result.error) {
      setAction({ status: "error", message: result.error });
      setHandoffs([]);
      return;
    }
    setHandoffs(result.data);
  }

  async function selectHandoff(id: string) {
    setSelectedId(id);
    setShowDismiss(false);
    setShowConvert(false);
    setDismissReason("");
    setConversionNote("");
    setAiSuggestion(null);
    setDraftResult(null);
    setDecisionNote("");
    setDecisionResult(null);
    setAction({ status: "idle", message: "" });
    setIsLoadingDetail(true);
    const result = await getRfqHandoff(id);
    setIsLoadingDetail(false);
    if (result.error || !result.data) {
      setDetail(null);
      setAction({ status: "error", message: result.error ?? "Could not load handoff detail." });
      return;
    }
    setDetail(result.data);
  }

  function applyUpdated(updated: RfqHandoff, message: string) {
    setHandoffs((prev) => prev.map((h) => (h.id === updated.id ? updated : h)));
    setDetail(updated);
    setShowDismiss(false);
    setShowConvert(false);
    setAction({ status: "success", message });
  }

  async function submitStartReview() {
    if (!detail) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Taking handoff into review…" });
    const result = await startReviewRfqHandoff(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Start review was not accepted by the backend." });
      return;
    }
    applyUpdated(result.data, "Handoff moved to In review.");
  }

  async function submitDismiss() {
    if (!detail || !dismissReason.trim()) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Dismissing handoff…" });
    const result = await dismissRfqHandoff(detail.id, dismissReason.trim());
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Dismiss was not accepted by the backend." });
      return;
    }
    applyUpdated(result.data, "Handoff dismissed.");
  }

  async function submitMarkConverted() {
    const note = conversionNote.trim();
    if (!detail || !note) {
      setAction({ status: "error", message: "A close note is required." });
      return;
    }
    setIsMutating(true);
    setAction({ status: "loading", message: "Closing handoff without draft…" });
    const result = await markConvertedRfqHandoff(detail.id, note);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Close without draft was not accepted by the backend." });
      return;
    }
    applyUpdated(result.data, "Handoff closed without draft.");
  }

  async function submitGenerateAiSuggestion() {
    if (!detail) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Generating advisory suggestion..." });
    const result = await generateRfqHandoffAiSuggestion(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "AI suggestion was not accepted by the backend." });
      return;
    }
    setAiSuggestion(result.data);
    setAction({ status: "success", message: "Advisory suggestion generated. No business state was changed." });
  }

  async function submitCreateDraftQuote() {
    if (!detail) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Creating a review-required draft quote..." });
    const result = await createDraftQuoteFromRfqHandoff(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({
        status: "error",
        message: result.error ?? "Draft quote creation was not accepted by the backend."
      });
      return;
    }
    setDraftResult(result.data);
    applyUpdated(
      result.data.handoff,
      `Draft quote ${result.data.draftQuote.quoteNumber} created for operator review.`
    );
  }

  async function submitDraftDecision(decision: RfqHandoffDecision) {
    const note = decisionNote.trim();
    if (!detail || !draftResult || !note || decisionResult) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Recording safe terminal demo decision..." });
    const result = await decideRfqHandoffDraft(detail.id, decision, note);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({
        status: "error",
        message: result.error ?? "Operator decision was not accepted by the backend."
      });
      return;
    }
    setDecisionResult(result.data);
    setAction({
      status: "success",
      message: `Decision ${result.data.decision} recorded. External execution remains disabled.`
    });
  }

  const canStartReview = detail?.status === "PENDING_REVIEW";
  const canAct = detail ? !isTerminal(detail.status) : false;
  const canCreateDraftQuote = detail?.status === "IN_REVIEW";

  return (
    <div className="review-workspace">
      {action.message ? (
        <section className="panel">
          <p
            className={`form-message ${
              action.status === "error" ? "error" : action.status === "success" ? "done" : ""
            }`}
          >
            {action.message}
          </p>
        </section>
      ) : null}

      {/* List */}
      <section className="panel table-panel">
        <div className="button-row">
          <h2>RFQ handoffs</h2>
          <Link className="button" href="/demo">Send deterministic demo RFQ</Link>
          <label htmlFor="rfq-status-filter" className="field-label">Filter by status</label>
          <select
            id="rfq-status-filter"
            className="form-input"
            value={statusFilter}
            disabled={busy}
            onChange={(e) => void applyFilter(e.target.value as StatusFilter)}
          >
            {STATUS_FILTERS.map((s) => (
              <option key={s} value={s}>{s === "all" ? "All" : statusLabel(s)}</option>
            ))}
          </select>
        </div>
        {isLoadingList ? (
          <p className="muted-copy">Loading handoffs…</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Channel</th>
                <th>Sender</th>
                <th>Request</th>
                <th>Intent</th>
                <th>Status</th>
                <th>Created</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {handoffs.length === 0 ? (
                <tr>
                  <td colSpan={7}>
                    No RFQ handoffs for this filter. Handoffs are created only from verified
                    Telegram/channel RFQ messages — they are never created manually here.
                  </td>
                </tr>
              ) : (
                handoffs.map((h) => (
                  <tr key={h.id} className={selectedId === h.id ? "selected-row" : ""}>
                    <td>{h.sourceChannel}</td>
                    <td>{h.sourceActorExternalId ?? "n/a"}</td>
                    <td>{h.requestPreview ?? h.requestText ?? "—"}</td>
                    <td>{h.detectedIntent ?? "—"}</td>
                    <td><span className={`status-pill ${statusClass(h.status)}`}>{statusLabel(h.status)}</span></td>
                    <td>{formatTimestamp(h.createdAt)}</td>
                    <td>
                      <button className="button" type="button" disabled={busy} onClick={() => void selectHandoff(h.id)}>
                        Open
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </section>

      {/* Detail / actions */}
      {isLoadingDetail ? (
        <section className="panel">
          <p className="muted-copy">Loading handoff detail…</p>
        </section>
      ) : detail ? (
        <>
        <RfqCockpitJourney
          detail={detail}
          aiSuggestion={aiSuggestion}
          draftResult={draftResult}
          decisionResult={decisionResult}
        />
        <section className="panel action-panel">
          <h2>Handoff detail</h2>
          <dl className="detail-list">
            <div><dt>Status</dt><dd><span className={`status-pill ${statusClass(detail.status)}`}>{statusLabel(detail.status)}</span></dd></div>
            <div><dt>Source channel</dt><dd>{detail.sourceChannel}</dd></div>
            <div><dt>Sender / contact hint</dt><dd>{detail.sourceActorExternalId ?? "n/a"}</dd></div>
            <div><dt>Detected intent</dt><dd>{detail.detectedIntent ?? "—"}</dd></div>
            <div><dt>Request text</dt><dd>{detail.requestText ?? "—"}</dd></div>
            {detail.reviewStartedAt ? (<div><dt>Review started</dt><dd>{formatTimestamp(detail.reviewStartedAt)}</dd></div>) : null}
            {detail.dismissReason ? (<div><dt>Dismiss reason</dt><dd>{detail.dismissReason}</dd></div>) : null}
            {detail.dismissedAt ? (<div><dt>Dismissed at</dt><dd>{formatTimestamp(detail.dismissedAt)}</dd></div>) : null}
            {detail.conversionNote ? (<div><dt>Conversion note</dt><dd>{detail.conversionNote}</dd></div>) : null}
            {detail.convertedAt ? (<div><dt>Converted at</dt><dd>{formatTimestamp(detail.convertedAt)}</dd></div>) : null}
            <div><dt>Created</dt><dd>{formatTimestamp(detail.createdAt)}</dd></div>
            <div><dt>Updated</dt><dd>{formatTimestamp(detail.updatedAt)}</dd></div>
          </dl>

          {isTerminal(detail.status) ? (
            <p className="muted-copy">
              This handoff is in a terminal state ({statusLabel(detail.status)}). No further transition is allowed.
            </p>
          ) : (
            <div className="button-row">
              <button
                className="button"
                type="button"
                disabled={busy || !canStartReview}
                onClick={() => { setShowDismiss(false); setShowConvert(false); void submitStartReview(); }}
              >
                {isMutating ? "Working…" : "Start review"}
              </button>
              <button
                className="button"
                type="button"
                disabled={busy || !canAct}
                onClick={() => { setShowDismiss(true); setShowConvert(false); }}
              >
                Dismiss…
              </button>
              <button
                className="button"
                type="button"
                disabled={busy || !canAct}
                onClick={() => void submitGenerateAiSuggestion()}
              >
                Generate suggestion
              </button>
              <button
                className="button"
                type="button"
                disabled={busy || !canCreateDraftQuote}
                onClick={() => void submitCreateDraftQuote()}
              >
                Create draft quote
              </button>
              <button
                className="button secondary-button"
                type="button"
                disabled={busy || !canAct}
                onClick={() => { setShowConvert(true); setShowDismiss(false); }}
              >
                Close without draft…
              </button>
            </div>
          )}

          {aiSuggestion ? (
            <div className="control-grid">
              <h3>Advisory suggestion</h3>
              <AiWorkSchemaV1View suggestion={aiSuggestion} />
              <p className="muted-copy">
                This suggestion is advisory. The operator can create or decline the draft independently.
              </p>
            </div>
          ) : null}

          {draftResult ? (
            <div className="control-grid">
              <h3>Draft quote created</h3>
              <dl className="detail-list">
                <div><dt>Quote</dt><dd>{draftResult.draftQuote.quoteNumber}</dd></div>
                <div><dt>Status</dt><dd>{draftResult.draftQuote.status}</dd></div>
                <div><dt>Validation</dt><dd>{draftResult.draftQuote.validationStatus}</dd></div>
                <div><dt>Human review</dt><dd>{draftResult.draftQuote.requiresHumanReview ? "Required" : "Not required"}</dd></div>
                <div><dt>Audit</dt><dd>{draftResult.auditStatus}</dd></div>
                <div><dt>Outbox</dt><dd>{draftResult.outboxStatus}</dd></div>
                <div><dt>External write safety</dt><dd>{draftResult.externalWriteSafety}</dd></div>
              </dl>
              {draftResult.draftQuote.lines.length ? (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>SKU / raw text</th>
                      <th>Product</th>
                      <th>Qty</th>
                      <th>UOM</th>
                      <th>Unit price</th>
                      <th>Stock</th>
                      <th>Validation</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draftResult.draftQuote.lines.map((line) => (
                      <tr key={line.id}>
                        <td>{line.lineNumber}</td>
                        <td>
                          {line.rawSku ?? "—"}
                          {line.rawText && line.rawText !== line.rawSku ? (
                            <span className="muted-copy"> · {line.rawText}</span>
                          ) : null}
                        </td>
                        <td>
                          {line.productName ?? "Unresolved"}
                          {line.normalizedSku ? (
                            <span className="muted-copy"> · {line.normalizedSku}</span>
                          ) : null}
                        </td>
                        <td>{formatMaybeNumber(line.quantity)}</td>
                        <td>{line.uom}</td>
                        <td>{formatMaybeNumber(line.unitPrice)}</td>
                        <td>{formatMaybeNumber(line.availableStock)}</td>
                        <td>
                          {line.validationStatus}
                          {formatIssueCodes(line.issueCodes) ? (
                            <span className="muted-copy"> · {formatIssueCodes(line.issueCodes)}</span>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="risk-note">No quote lines were returned. Treat this draft as not business-meaningful until reviewed.</p>
              )}

              {draftResult.draftQuote.issues.length ? (
                <div>
                  <h4>Validation issues</h4>
                  <ul>
                    {draftResult.draftQuote.issues.map((issue) => (
                      <li key={issue.id}>
                        {issue.issueCode}: {issue.message}
                        {issue.blocking ? " — blocking" : ""}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              {decisionResult ? (
                <div className="control-grid">
                  <h4>Operator decision completed</h4>
                  <dl className="detail-list">
                    <div><dt>Decision state</dt><dd>{decisionResult.decision}</dd></div>
                    <div><dt>Quote state</dt><dd>{decisionResult.quoteState}</dd></div>
                    <div><dt>Safe terminal state</dt><dd>{decisionResult.terminalState}</dd></div>
                    <div><dt>Audit</dt><dd>{decisionResult.auditStatus}</dd></div>
                    <div><dt>External execution</dt><dd>{decisionResult.externalExecution}</dd></div>
                    <div><dt>Connector call</dt><dd>{decisionResult.connectorAction}</dd></div>
                    <div><dt>Outbox</dt><dd>{decisionResult.outboxStatus}</dd></div>
                    <div><dt>Safety summary</dt><dd>{decisionResult.safetySummary}</dd></div>
                  </dl>
                  <p className="risk-note">
                    This is a safe demo terminal state, not quote approval. No ERP, 1C, accounting,
                    connector, inventory, pricing, or customer-master write was requested.
                  </p>
                </div>
              ) : (
                <div className="control-grid">
                  <h4>Operator decision</h4>
                  <p className="risk-note">
                    Complete or decline this local demo flow. Neither choice approves the quote or
                    enables external execution.
                  </p>
                  <label className="field-label" htmlFor="rfq-decision-note">
                    Decision note (required)
                  </label>
                  <input
                    id="rfq-decision-note"
                    className="form-input"
                    type="text"
                    maxLength={500}
                    placeholder="e.g. reviewed for demo; no external action requested"
                    value={decisionNote}
                    disabled={busy}
                    onChange={(e) => setDecisionNote(e.target.value)}
                  />
                  <div className="button-row">
                    <button
                      className="button"
                      type="button"
                      disabled={busy || !decisionNote.trim()}
                      onClick={() => void submitDraftDecision("COMPLETE_DEMO")}
                    >
                      Complete safe demo
                    </button>
                    <button
                      className="button secondary-button"
                      type="button"
                      disabled={busy || !decisionNote.trim()}
                      onClick={() => void submitDraftDecision("DECLINE_DEMO")}
                    >
                      Decline demo draft
                    </button>
                  </div>
                  <p className="muted-copy">
                    externalExecution = DISABLED · connector call = NOT_INVOKED
                  </p>
                </div>
              )}

            </div>
          ) : null}

          {showDismiss ? (
            <div className="control-grid">
              <label className="field-label" htmlFor="rfq-dismiss-reason">Dismiss reason (required)</label>
              <input
                id="rfq-dismiss-reason"
                className="form-input"
                type="text"
                placeholder="e.g. spam / not an RFQ / duplicate request"
                value={dismissReason}
                disabled={busy}
                onChange={(e) => setDismissReason(e.target.value)}
              />
              <div className="button-row">
                <button className="button" type="button" disabled={busy || !dismissReason.trim()} onClick={() => void submitDismiss()}>
                  {isMutating ? "Dismissing…" : "Confirm dismiss"}
                </button>
                <button className="button" type="button" disabled={busy} onClick={() => { setShowDismiss(false); setDismissReason(""); }}>Cancel</button>
              </div>
            </div>
          ) : null}

          {showConvert ? (
            <div className="control-grid">
              <p className="risk-note">
                This closes the handoff without creating a draft quote. It is not equivalent to the
                Create draft quote action.
              </p>
              <label className="field-label" htmlFor="rfq-conversion-note">Close note (required)</label>
              <input
                id="rfq-conversion-note"
                className="form-input"
                type="text"
                placeholder="e.g. handled outside Operant / duplicate / manual quote already created"
                value={conversionNote}
                disabled={busy}
                onChange={(e) => setConversionNote(e.target.value)}
              />
              <div className="button-row">
                <button className="button secondary-button" type="button" disabled={busy || !conversionNote.trim()} onClick={() => void submitMarkConverted()}>
                  {isMutating ? "Working…" : "Confirm close without draft"}
                </button>
                <button className="button" type="button" disabled={busy} onClick={() => { setShowConvert(false); setConversionNote(""); }}>Cancel</button>
              </div>
            </div>
          ) : null}

          <p className="risk-note">
            Draft creation produces an internal, review-required quote only. It does not approve anything,
            update inventory/price/customer data, create an outbox command, or trigger any ERP/external write.
            All transitions are tenant-scoped, idempotent, and audited by backend services.
          </p>
        </section>
        </>
      ) : null}
    </div>
  );
}
