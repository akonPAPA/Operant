"use client";

// OP-CAP-15A/15B — create-draft controls for the operator validation review workspace.
// Narrow client component wired ONLY to the OP-CAP-15A/15B draft endpoints via
// lib/validation-review-draft-command-api.ts. It surfaces whether a draft already exists, lets the
// operator create an internal Draft Quote / Draft Order — optionally from a selected subset of validated
// lines and with a bounded operator note. It performs no final/approved order and no ERP/1C/connector/
// master-data write. Buttons are blocked when unresolved blocking issues exist or a draft already exists.

import { useMemo, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { ValidationReviewDetail } from "@/lib/validation-review-detail-api";
import {
  createDraftOrderFromReview,
  createDraftQuoteFromReview,
  type CreateDraftOptions,
  type ValidationReviewDraftStatus,
  type ValidationReviewDraftabilityResponse,
  type ValidationReviewLineDraftability,
  type ValidationReviewLineRemediation
} from "@/lib/validation-review-draft-command-api";

const MAX_OPERATOR_NOTE = 1000;

// OP-CAP-15C — advisory reason-code labels. Rendered as plain text only (never HTML).
const REASON_TEXT: Record<string, string> = {
  LINE_READY: "Ready",
  BLOCKING_ISSUE_UNRESOLVED: "Unresolved blocking issue",
  WARNING_ISSUE_PRESENT: "Has warning",
  SKU_NOT_VALIDATED: "SKU not validated",
  QUANTITY_NOT_NORMALIZED: "Quantity not normalized",
  UOM_NOT_NORMALIZED: "Unit of measure not normalized",
  PRODUCT_MATCH_MISSING: "Product match missing",
  LINE_ALREADY_INCLUDED_IN_EXISTING_DRAFT: "Already in draft",
  CASE_NOT_DRAFTABLE: "Review not draftable yet",
  NO_DRAFTABLE_LINE_ITEMS: "No draftable lines",
  UNKNOWN_BLOCKER: "Blocked"
};

function reasonText(code: string) {
  return REASON_TEXT[code] ?? code;
}

function readinessPill(hint: ValidationReviewLineDraftability) {
  if (hint.alreadyDrafted) return "Already drafted";
  if (hint.severity === "BLOCKED") return "Blocked";
  if (hint.severity === "WARNING") return "Warning";
  return "Ready";
}

// OP-CAP-15D — label for the remediation link. Each maps to the EXISTING OP-CAP-14C operator controls,
// which the operator completes in the "Operator review actions" panel (#operator-review-actions). These
// links are an advisory convenience — the backend command + draft-create endpoints remain authoritative.
const REMEDIATION_ACTIONS_ANCHOR = "#operator-review-actions";

function remediationLabel(remediationType: string): string | null {
  switch (remediationType) {
    case "RESOLVE_ISSUE":
      return "Resolve validation issue";
    case "CORRECT_LINE":
      return "Correct line item";
    case "CORRECT_FIELD":
      return "Correct extracted field";
    case "REQUEST_APPROVAL":
      return "Request approval";
    case "VIEW_ISSUE":
      return "View issue";
    default:
      return null;
  }
}

// OP-CAP-15D/15E — build a deep-target link to the existing OP-CAP-14C panel. Target ids ride in search
// params (read by ValidationReviewActionsClient) and the URL hash scrolls to the panel. These ids are an
// advisory UX convenience only: the 14C client validates them against the tenant-scoped review detail and
// the backend command + draft-create endpoints stay the final authority.
function remediationHref(pathname: string, remediation: ValidationReviewLineRemediation): string {
  const params = new URLSearchParams();
  if (remediation.remediationType) params.set("reviewActionType", remediation.remediationType);
  if (remediation.targetIssueId) params.set("reviewActionIssueId", remediation.targetIssueId);
  if (remediation.targetLineItemId) params.set("reviewActionLineItemId", remediation.targetLineItemId);
  const query = params.toString();
  return `${pathname}${query ? `?${query}` : ""}${REMEDIATION_ACTIONS_ANCHOR}`;
}

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };
type ExistingDraft = { draftType: string; draftId: string; workspacePath?: string | null };

export function ValidationReviewDraftControls({
  detail,
  initialDraftStatus,
  draftability
}: Readonly<{
  detail: ValidationReviewDetail;
  initialDraftStatus?: ValidationReviewDraftStatus | null;
  draftability?: ValidationReviewDraftabilityResponse | null;
}>) {
  const router = useRouter();
  const pathname = usePathname();
  const validationRunId = detail.validationRun.validationRunId;
  const blockingCount = detail.validationRun.blockingIssueCount;
  const warningCount = detail.validationRun.warningReviewIssueCount;
  const blocked = blockingCount > 0;

  const initialExisting: ExistingDraft | null =
    initialDraftStatus?.exists && initialDraftStatus.draftId && initialDraftStatus.draftType
      ? { draftType: initialDraftStatus.draftType, draftId: initialDraftStatus.draftId, workspacePath: initialDraftStatus.workspacePath }
      : null;

  // OP-CAP-15C — advisory per-line hints. A line is selectable only when not BLOCKED and not already drafted.
  const hintById = useMemo(() => {
    const map = new Map<string, ValidationReviewLineDraftability>();
    (draftability?.lines ?? []).forEach((line) => map.set(line.lineItemId, line));
    return map;
  }, [draftability]);

  function lineSelectable(lineItemId: string) {
    const hint = hintById.get(lineItemId);
    return hint ? hint.severity !== "BLOCKED" && !hint.alreadyDrafted : true;
  }

  const [existing, setExisting] = useState<ExistingDraft | null>(initialExisting);
  const [state, setState] = useState<ActionState>({ status: "idle", message: "" });
  const [selectionMode, setSelectionMode] = useState(false);
  // Default selection excludes lines the backend hints are not draftable (advisory; backend re-validates).
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>(() =>
    detail.lineItems
      .map((l) => l.lineItemId)
      .filter((id) => {
        const hint = draftability?.lines.find((line) => line.lineItemId === id);
        return hint ? hint.severity !== "BLOCKED" && !hint.alreadyDrafted : true;
      })
  );
  const [operatorNote, setOperatorNote] = useState("");

  const submitting = state.status === "loading";
  const alreadyCreated = existing !== null;

  // OP-CAP-15F — post-remediation continuity. Return-to-draft params are advisory UX only: the line id is
  // honored ONLY when it matches a line in the backend-provided, tenant-scoped draftability data AND that
  // line is now draftable (not BLOCKED, not already drafted). Malformed/stale/foreign ids simply show
  // nothing. Computed during render — no state, no effect, no security role.
  const searchParams = useSearchParams();
  const returnLineItemId = searchParams.get("reviewReturnToDraft") === "1" ? searchParams.get("reviewReturnLineItemId") : null;
  const returnHint = returnLineItemId ? hintById.get(returnLineItemId) ?? null : null;
  const continuityHint = returnHint && !returnHint.alreadyDrafted && returnHint.severity !== "BLOCKED" ? returnHint : null;

  const selectionInvalid = useMemo(() => selectionMode && selectedLineIds.length === 0, [selectionMode, selectedLineIds]);
  const disableCreate = blocked || submitting || alreadyCreated || selectionInvalid;

  function toggleLine(lineItemId: string) {
    if (!lineSelectable(lineItemId)) return; // blocked / already-drafted lines cannot be selected
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
    // id anchor lets OP-CAP-15F post-remediation continuity links scroll back to these draft controls.
    <section className="panel action-panel" id="validation-review-draft-controls">
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

          {/* OP-CAP-15F — subtle continuity affordance after a completed remediation. Shown only when the
              returned line exists in tenant-scoped draftability data and is now draftable. */}
          {continuityHint ? (
            <p className="form-message done continuity-note">
              Line #{continuityHint.lineNumber} is now draftable. Continue draft.
              {selectionMode && !selectedLineIds.includes(continuityHint.lineItemId) ? (
                <>
                  {" "}
                  <button className="button" type="button" onClick={() => toggleLine(continuityHint.lineItemId)} disabled={submitting}>
                    Select this line
                  </button>
                </>
              ) : null}
            </p>
          ) : null}

          {/* OP-CAP-15C — compact advisory line readiness (Ready / Warning / Blocked / Already drafted). */}
          {draftability && draftability.lines.length > 0 ? (
            <div className="action-block">
              <h3 className="subhead">Line readiness</h3>
              <ul className="line-readiness-list">
                {draftability.lines.map((hint) => (
                  <li key={hint.lineItemId}>
                    <span className={`status-pill readiness-${hint.alreadyDrafted ? "drafted" : hint.severity.toLowerCase()}`}>
                      {readinessPill(hint)}
                    </span>{" "}
                    #{hint.lineNumber} {hint.normalizedSku ?? ""}
                    {hint.severity !== "OK" || hint.alreadyDrafted ? (
                      <span className="muted-copy"> — {hint.reasons.map(reasonText).join(", ")}</span>
                    ) : null}
                    {/* OP-CAP-15D — advisory remediation links to existing OP-CAP-14C controls (plain text). */}
                    {hint.remediations.length > 0 ? (
                      <span className="remediation-actions">
                        {hint.remediations.map((remediation) => {
                          const label = remediationLabel(remediation.remediationType);
                          if (!label) return null;
                          return (
                            <Link key={`${hint.lineItemId}-${remediation.reasonCode}`} className="remediation-link" href={remediationHref(pathname, remediation)} title={remediation.recommendedAction}>
                              {label}
                            </Link>
                          );
                        })}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
              {!draftability.caseDraftable ? (
                <p className="muted-copy">This review is not draftable yet. The backend will re-check on submit.</p>
              ) : null}
            </div>
          ) : null}

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
                  {detail.lineItems.map((line) => {
                    const hint = hintById.get(line.lineItemId);
                    const disabledLine = submitting || !lineSelectable(line.lineItemId);
                    return (
                      <li key={line.lineItemId}>
                        <label>
                          <input
                            type="checkbox"
                            checked={selectedLineIds.includes(line.lineItemId)}
                            onChange={() => toggleLine(line.lineItemId)}
                            disabled={disabledLine}
                          />{" "}
                          #{line.lineNumber} {line.rawSku ?? ""}
                          {hint && (hint.severity === "BLOCKED" || hint.alreadyDrafted) ? (
                            <span className="muted-copy"> — {readinessPill(hint)}</span>
                          ) : null}
                        </label>
                      </li>
                    );
                  })}
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
