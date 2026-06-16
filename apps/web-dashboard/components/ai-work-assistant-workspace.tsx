"use client";

import { useState } from "react";
import {
  acceptAiWorkSuggestion,
  extractNextActions,
  listRecentAiWork,
  rejectAiWorkSuggestion,
  riskClass,
  statusClass,
  workTypeLabel,
  type AiWorkSuggestion,
} from "@/lib/ai-work-api";

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

export function AiWorkAssistantWorkspace({
  initialSuggestions,
  initialError
}: Readonly<{
  initialSuggestions: AiWorkSuggestion[];
  initialError?: string;
}>) {
  const [suggestions, setSuggestions] = useState<AiWorkSuggestion[]>(initialSuggestions);
  const [selectedId, setSelectedId] = useState<string | null>(initialSuggestions[0]?.id ?? null);
  const [action, setAction] = useState<ActionState>({
    status: initialError ? "error" : "idle",
    message: initialError ?? ""
  });
  const [busy, setBusy] = useState(false);

  const selected = suggestions.find((s) => s.id === selectedId) ?? null;

  async function refresh() {
    const { data, error } = await listRecentAiWork(50);
    setSuggestions(data);
    if (error) setAction({ status: "error", message: error });
  }

  async function onAccept(id: string) {
    setBusy(true);
    setAction({ status: "loading", message: "Recording acceptance…" });
    const { data, error } = await acceptAiWorkSuggestion(id, { reason: "Accepted by operator" });
    applyDecision(data, error, "Suggestion accepted (advisory only — no business state changed).");
  }

  async function onReject(id: string) {
    setBusy(true);
    setAction({ status: "loading", message: "Recording rejection…" });
    const { data, error } = await rejectAiWorkSuggestion(id, { reason: "Rejected by operator" });
    applyDecision(data, error, "Suggestion rejected.");
  }

  function applyDecision(data: AiWorkSuggestion | null, error: string | undefined, okMessage: string) {
    if (error || !data) {
      setAction({ status: "error", message: error ?? "Decision failed." });
    } else {
      setSuggestions((prev) => prev.map((s) => (s.id === data.id ? data : s)));
      setAction({ status: "success", message: okMessage });
    }
    setBusy(false);
  }

  return (
    <div className="demo-stack">
      <section className="panel">
        <h2>Advisory suggestion review</h2>
        <p className="risk-note">
          The AI Work Assistant produces advisory suggestions only. It never approves quotes, orders,
          discounts, or substitutes, and never writes to ERP or business records. Every suggestion is
          anchored to a backend-resolved source object and must be reviewed by a human before any
          action is taken.
        </p>
        <div className="tag-row">
          <button type="button" className="secondary" onClick={refresh} disabled={busy}>Refresh list</button>
        </div>
        {action.status !== "idle" && (
          <p className={`status-pill ${action.status === "error" ? "error" : action.status === "success" ? "ok" : "warning"}`}>
            {action.message}
          </p>
        )}
      </section>

      <div className="split-2">
        <section className="panel">
          <h2>Recent suggestions</h2>
          {suggestions.length === 0 ? (
            <p className="muted">No advisory suggestions are available for the active review context.</p>
          ) : (
            <ul className="record-list">
              {suggestions.map((s) => (
                <li key={s.id}>
                  <button
                    type="button"
                    className={s.id === selectedId ? "record-item selected" : "record-item"}
                    onClick={() => setSelectedId(s.id)}
                  >
                    <span className="record-title">{workTypeLabel(s.workType)}</span>
                    <span className="tag-row">
                      <span className={`status-pill ${statusClass(s.status)}`}>{s.status}</span>
                      <span className={`status-pill ${riskClass(s.riskLevel)}`}>Risk: {s.riskLevel}</span>
                    </span>
                    <span className="muted">{s.sourceType} · {formatTimestamp(s.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel">
          <h2>Suggestion detail</h2>
          {!selected ? (
            <p className="muted">Select a suggestion to review its advisory content.</p>
          ) : (
            <AiWorkDetail suggestion={selected} busy={busy} onAccept={onAccept} onReject={onReject} />
          )}
        </section>
      </div>
    </div>
  );
}

function AiWorkDetail({
  suggestion,
  busy,
  onAccept,
  onReject
}: Readonly<{
  suggestion: AiWorkSuggestion;
  busy: boolean;
  onAccept: (id: string) => void;
  onReject: (id: string) => void;
}>) {
  const nextActions = suggestion.workType === "NEXT_ACTION_SUGGESTION"
    ? extractNextActions(suggestion.structuredPayloadJson)
    : [];
  const isDraft = suggestion.workType === "CUSTOMER_REPLY_DRAFT";
  const decided = suggestion.status !== "GENERATED";

  return (
    <div className="demo-stack">
      <div className="tag-row">
        <span className={`status-pill ${statusClass(suggestion.status)}`}>{suggestion.status}</span>
        <span className={`status-pill ${riskClass(suggestion.riskLevel)}`}>Risk: {suggestion.riskLevel}</span>
        <span className="status-pill">Advisory only</span>
        {isDraft && <span className="status-pill warning">Draft only — not sent</span>}
      </div>

      <h3>{workTypeLabel(suggestion.workType)}</h3>
      <p className="generated-text">{suggestion.generatedText}</p>

      {nextActions.length > 0 && (
        <div>
          <h4>Next-action candidates</h4>
          <ul className="record-list">
            {nextActions.map((a) => (
              <li key={a.actionCode} className="record-item">
                <span className="record-title">{a.label}</span>
                <span className="muted">{a.actionCode}</span>
                {a.requiresHumanApproval && <span className="status-pill warning">Requires human approval</span>}
              </li>
            ))}
          </ul>
          <p className="risk-note">
            These candidates are suggestions only. Selecting one does not execute it. Any real action
            (creating a quote draft, reviewing a discount, etc.) must be performed through its own
            workflow with its own validation and approval.
          </p>
        </div>
      )}

      <dl className="detail-grid">
        <dt>Source</dt><dd>{suggestion.sourceType}</dd>
        <dt>Strategy version</dt><dd>{suggestion.strategyVersion}</dd>
        {typeof suggestion.confidence === "number" && (
          <>
            <dt>Confidence</dt><dd>{suggestion.confidence.toFixed(2)}</dd>
          </>
        )}
        <dt>Created</dt><dd>{formatTimestamp(suggestion.createdAt)}</dd>
        {decided && (
          <>
            <dt>Decision</dt>
            <dd>{suggestion.status} · {formatTimestamp(suggestion.decidedAt)}{suggestion.decisionReason ? ` · ${suggestion.decisionReason}` : ""}</dd>
          </>
        )}
      </dl>

      <div className="tag-row">
        <button type="button" onClick={() => onAccept(suggestion.id)} disabled={busy || decided}>
          Accept (advisory)
        </button>
        <button type="button" className="secondary" onClick={() => onReject(suggestion.id)} disabled={busy || decided}>
          Reject
        </button>
      </div>
      {decided && <p className="muted">This suggestion has been {suggestion.status.toLowerCase()} and is locked.</p>}
    </div>
  );
}
