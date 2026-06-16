"use client";

import { useState } from "react";
import {
  dismissRfqHandoff,
  generateRfqHandoffAiSuggestion,
  getRfqHandoff,
  isTerminal,
  listRfqHandoffs,
  markConvertedRfqHandoff,
  startReviewRfqHandoff,
  statusClass,
  statusLabel,
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
    if (!detail) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Marking handoff converted…" });
    const result = await markConvertedRfqHandoff(detail.id, conversionNote.trim() || undefined);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Mark converted was not accepted by the backend." });
      return;
    }
    applyUpdated(result.data, "Handoff marked converted (ready for a later, separately-gated quote workflow).");
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

  const canStartReview = detail?.status === "PENDING_REVIEW";
  const canAct = detail ? !isTerminal(detail.status) : false;

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
        <section className="panel action-panel">
          <h2>Handoff detail</h2>
          <dl className="detail-list">
            <div><dt>Status</dt><dd><span className={`status-pill ${statusClass(detail.status)}`}>{statusLabel(detail.status)}</span></dd></div>
            <div><dt>Source channel</dt><dd>{detail.sourceChannel}</dd></div>
            <div><dt>Source event ID</dt><dd>{detail.sourceExternalEventId ?? "n/a"}</dd></div>
            <div><dt>Sender / contact hint</dt><dd>{detail.sourceActorExternalId ?? "n/a"}</dd></div>
            <div><dt>Detected intent</dt><dd>{detail.detectedIntent ?? "—"}</dd></div>
            <div><dt>Request text</dt><dd>{detail.requestText ?? "—"}</dd></div>
            {detail.reviewerUserId ? (<div><dt>Reviewer</dt><dd>{detail.reviewerUserId}</dd></div>) : null}
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
                onClick={() => { setShowConvert(true); setShowDismiss(false); }}
              >
                Mark converted…
              </button>
              <button
                className="button"
                type="button"
                disabled={busy || !canAct}
                onClick={() => void submitGenerateAiSuggestion()}
              >
                Generate suggestion
              </button>
            </div>
          )}

          {aiSuggestion ? (
            <div className="control-grid">
              <h3>Advisory suggestion</h3>
              <p className="generated-text">{aiSuggestion.generatedText}</p>
              <div className="tag-row">
                <span className="status-pill">Advisory only</span>
                <span className="status-pill warning">Risk: {aiSuggestion.riskLevel}</span>
              </div>
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
              <label className="field-label" htmlFor="rfq-conversion-note">Conversion note (optional)</label>
              <input
                id="rfq-conversion-note"
                className="form-input"
                type="text"
                placeholder="e.g. validated, ready for manual quote"
                value={conversionNote}
                disabled={busy}
                onChange={(e) => setConversionNote(e.target.value)}
              />
              <div className="button-row">
                <button className="button" type="button" disabled={busy} onClick={() => void submitMarkConverted()}>
                  {isMutating ? "Working…" : "Confirm mark converted"}
                </button>
                <button className="button" type="button" disabled={busy} onClick={() => { setShowConvert(false); setConversionNote(""); }}>Cancel</button>
              </div>
            </div>
          ) : null}

          <p className="risk-note">
            Mark converted records that the operator review is complete and the request is ready for a later,
            separately-gated quote/order workflow. It does not create a quote or order, approve anything,
            update inventory/price/customer data, or trigger any ERP/external write. All transitions are
            tenant-scoped and audited by the backend command service.
          </p>
        </section>
      ) : null}
    </div>
  );
}
