"use client";

// OP-CAP-15A/15B — create-draft controls for the operator validation review workspace.
// Narrow client component wired ONLY to the OP-CAP-15A/15B draft endpoints via
// lib/validation-review-draft-command-api.ts. It surfaces whether a draft already exists, lets the
// operator create an internal Draft Quote / Draft Order — optionally from a selected subset of validated
// lines and with a bounded operator note. It performs no final/approved order and no ERP/1C/connector/
// master-data write. Buttons are blocked when unresolved blocking issues exist or a draft already exists.

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import type { ValidationReviewDetail } from "@/lib/validation-review-detail-api";
import {
  createDraftOrderFromReview,
  createDraftQuoteFromReview,
  type CreateDraftOptions,
  type ValidationReviewDraftStatus
} from "@/lib/validation-review-draft-command-api";

const MAX_OPERATOR_NOTE = 1000;

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };
type ExistingDraft = { draftType: string; draftId: string; workspacePath?: string | null };

export function ValidationReviewDraftControls({
  detail,
  initialDraftStatus
}: Readonly<{ detail: ValidationReviewDetail; initialDraftStatus?: ValidationReviewDraftStatus | null }>) {
  const router = useRouter();
  const validationRunId = detail.validationRun.validationRunId;
  const blockingCount = detail.validationRun.blockingIssueCount;
  const warningCount = detail.validationRun.warningReviewIssueCount;
  const blocked = blockingCount > 0;

  const initialExisting: ExistingDraft | null =
    initialDraftStatus?.exists && initialDraftStatus.draftId && initialDraftStatus.draftType
      ? { draftType: initialDraftStatus.draftType, draftId: initialDraftStatus.draftId, workspacePath: initialDraftStatus.workspacePath }
      : null;

  const [existing, setExisting] = useState<ExistingDraft | null>(initialExisting);
  const [state, setState] = useState<ActionState>({ status: "idle", message: "" });
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>(() => detail.lineItems.map((l) => l.lineItemId));
  const [operatorNote, setOperatorNote] = useState("");

  const submitting = state.status === "loading";
  const alreadyCreated = existing !== null;

  const selectionInvalid = useMemo(() => selectionMode && selectedLineIds.length === 0, [selectionMode, selectedLineIds]);
  const disableCreate = blocked || submitting || alreadyCreated || selectionInvalid;

  function toggleLine(lineItemId: string) {
    setSelectedLineIds((prev) => (prev.includes(lineItemId) ? prev.filter((id) => id !== lineItemId) : [...prev, lineItemId]));
  }

  async function create(kind: "QUOTE" | "ORDER") {
    if (alreadyCreated) return;
    if (blocked) {
      setState({ status: "error", message: "Resolve all blocking validation issues before creating a draft." });
      return;
    }
    if (selectionInvalid) {
      setState({ status: "error", message: "Select at least one line, or turn off selected-lines mode." });
      return;
    }
    const options: CreateDraftOptions = {
      selectedLineIds: selectionMode ? selectedLineIds : undefined,
      operatorNote: operatorNote.trim() !== "" ? operatorNote : undefined
    };
    setState({ status: "loading", message: `Creating draft ${kind.toLowerCase()}…` });
    const result = kind === "QUOTE"
      ? await createDraftQuoteFromReview(validationRunId, options)
      : await createDraftOrderFromReview(validationRunId, options);
    if (result.error || !result.data) {
      setState({ status: "error", message: result.error ?? "Draft was not created by the backend." });
      return;
    }
    // Backend-confirmed: reflect existing-draft state (covers both created and idempotent alreadyExisted).
    setExisting({ draftType: result.data.draftType, draftId: result.data.draftId, workspacePath: result.data.nextRoute });
    const verb = result.data.alreadyExisted ? "already exists" : "created";
    setState({
      status: "success",
      message: `Draft ${result.data.draftType.toLowerCase()} ${verb} (${result.data.createdLineCount} line(s)).`
    });
    router.refresh();
  }

  return (
    <section className="panel action-panel">
      <h2>Create draft from this review</h2>
      <p className="risk-note">
        Creates an internal draft (quote or order) through the permissioned Core command service. This is not a final
        order and triggers no ERP/1C, connector or external write. Drafts use validated values from this review.
      </p>

      {alreadyCreated ? (
        <div className="form-message done">
          <span className="status-pill">Draft {existing!.draftType.toLowerCase()} already created</span>{" "}
          {existing!.workspacePath ? <a href={existing!.workspacePath}>Open draft {existing!.draftId.slice(0, 8)}…</a> : `Draft ${existing!.draftId.slice(0, 8)}…`}
          <p className="muted-copy">One draft per review. Resolve or open the existing draft instead of creating another.</p>
        </div>
      ) : (
        <>
          <p className="muted-copy">
            {blocked
              ? `Blocked: ${blockingCount} unresolved blocking issue(s) must be resolved first.`
              : warningCount > 0
                ? `${warningCount} warning issue(s) present — the backend may still require them resolved.`
                : "No blocking issues — this review is ready for an internal draft."}
          </p>

          {/* Selected-line subset (optional). Default: create from all validated lines. */}
          {detail.lineItems.length > 0 ? (
            <div className="action-block">
              <label>
                <input
                  type="checkbox"
                  checked={selectionMode}
                  onChange={(e) => setSelectionMode(e.target.checked)}
                  disabled={submitting}
                />{" "}
                Create from selected lines only
              </label>
              {selectionMode ? (
                <ul className="line-select-list">
                  {detail.lineItems.map((line) => (
                    <li key={line.lineItemId}>
                      <label>
                        <input
                          type="checkbox"
                          checked={selectedLineIds.includes(line.lineItemId)}
                          onChange={() => toggleLine(line.lineItemId)}
                          disabled={submitting}
                        />{" "}
                        #{line.lineNumber} {line.rawSku ?? ""}
                      </label>
                    </li>
                  ))}
                </ul>
              ) : null}
              <p className="muted-copy">
                {selectionMode ? `${selectedLineIds.length} of ${detail.lineItems.length} line(s) selected.` : `All ${detail.lineItems.length} validated line(s) will be included.`}
              </p>
            </div>
          ) : null}

          {/* Optional bounded operator note. Rendered as plain text only — never raw HTML. */}
          <div className="action-block">
            <label>
              Operator note (optional)
              <textarea
                value={operatorNote}
                maxLength={MAX_OPERATOR_NOTE}
                onChange={(e) => setOperatorNote(e.target.value)}
                disabled={submitting}
                rows={2}
              />
            </label>
            <p className="muted-copy">{operatorNote.length}/{MAX_OPERATOR_NOTE}</p>
          </div>

          <div className="button-row">
            <button className="button" type="button" onClick={() => void create("QUOTE")} disabled={disableCreate}>
              {submitting ? "Working…" : "Create draft quote"}
            </button>
            <button className="button" type="button" onClick={() => void create("ORDER")} disabled={disableCreate}>
              {submitting ? "Working…" : "Create draft order"}
            </button>
          </div>
        </>
      )}

      {state.message ? (
        <p className={`form-message ${state.status === "error" ? "error" : state.status === "success" ? "done" : ""}`}>
          {state.message}
          {state.status === "success" && existing?.workspacePath ? (
            <>
              {" "}
              <a href={existing.workspacePath}>Open draft {existing.draftId.slice(0, 8)}…</a>
            </>
          ) : null}
        </p>
      ) : null}
    </section>
  );
}
