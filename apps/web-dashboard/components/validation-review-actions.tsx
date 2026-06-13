"use client";

// OP-CAP-14D — operator validation review action controls.
// Narrow client component wired ONLY to the OP-CAP-14C command endpoints via
// lib/validation-review-command-api.ts. It corrects advisory fields/line items, resolves issues, and
// requests approval. It performs no final quote/order creation, no ERP/1C/connector/master-data write,
// and no approve or reject decision. State refreshes from the backend (router.refresh) after success —
// no faked local completion. No raw AI payload / document body / prompt / secret is rendered or edited.

import { useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { ValidationReviewDetail } from "@/lib/validation-review-detail-api";
import {
  requestValidationReviewApproval,
  resolveValidationReviewIssue,
  submitValidationReviewCorrection,
  type IssueResolution
} from "@/lib/validation-review-command-api";

const MAX_REASON = 512;
const MAX_VALUE = 512;
const MAX_UOM = 16;

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };

const IDLE: ActionState = { status: "idle", message: "" };

function feedbackClass(status: ActionStatus): string {
  return `form-message ${status === "error" ? "error" : status === "success" ? "done" : ""}`;
}

export function ValidationReviewActionsClient({ detail }: Readonly<{ detail: ValidationReviewDetail }>) {
  const router = useRouter();
  const validationRunId = detail.validationRun.validationRunId;

  // OP-CAP-15E — deep-target preselection from a remediation link's search params. Ids are advisory UX
  // only: they are accepted ONLY when they match a row already present in the tenant-scoped review detail,
  // so a malformed or foreign id simply does not preselect (no crash, no bypass). The backend command stays
  // the final authority on every submit.
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const rawIssueId = searchParams.get("reviewActionIssueId");
  const rawLineItemId = searchParams.get("reviewActionLineItemId");
  const reviewActionType = searchParams.get("reviewActionType");
  const targetIssueId = rawIssueId && detail.issues.some((i) => i.issueId === rawIssueId) ? rawIssueId : null;
  const targetLineItemId = rawLineItemId && detail.lineItems.some((l) => l.lineItemId === rawLineItemId) ? rawLineItemId : null;

  // OP-CAP-15F — post-remediation continuity. After a successful action that was deep-targeted at a line
  // (15E), put a narrow return-to-draft marker in the URL (search params carry metadata; the hash only
  // scrolls back to the draft controls) and keep the existing router.refresh() so review data and
  // draftability reload from the backend. With no deep-target line, behavior is exactly as before.
  function applied() {
    if (targetLineItemId) {
      const params = new URLSearchParams();
      params.set("reviewReturnToDraft", "1");
      params.set("reviewReturnLineItemId", targetLineItemId);
      params.set("reviewReturnReason", "remediation-completed");
      router.replace(`${pathname}?${params.toString()}#validation-review-draft-controls`);
    }
    router.refresh();
  }

  return (
    // id anchor lets OP-CAP-15D/15E draftability remediation links jump straight to these existing controls.
    <section className="panel action-panel" id="operator-review-actions">
      <h2>Operator review actions</h2>
      <p className="risk-note">
        Actions are applied through the permissioned Core command service (REVIEW_ACTION). They correct
        advisory extraction rows and review state only — no quote/order, ERP/1C, connector or master-data
        write. The view refreshes from the backend after each accepted action.
      </p>
      {/* OP-CAP-15E — keying on the deep-target re-mounts the control with the preselected target when a
          new remediation link is clicked (idiomatic "reset uncontrolled state via key"); with no target the
          key is stable so manual operator selection persists. */}
      <CorrectionForm key={`corr-${reviewActionType ?? ""}-${targetLineItemId ?? ""}`} detail={detail} validationRunId={validationRunId} onApplied={applied} targetLineItemId={targetLineItemId} reviewActionType={reviewActionType} />
      <IssueResolutionControls key={`issue-${targetIssueId ?? ""}`} detail={detail} validationRunId={validationRunId} onApplied={applied} targetIssueId={targetIssueId} />
      <ApprovalRequestControl key={`appr-${reviewActionType ?? ""}-${targetLineItemId ?? ""}`} detail={detail} validationRunId={validationRunId} onApplied={applied} targetLineItemId={targetLineItemId} reviewActionType={reviewActionType} />
    </section>
  );
}

function CorrectionForm({
  detail,
  validationRunId,
  onApplied,
  targetLineItemId,
  reviewActionType
}: Readonly<{ detail: ValidationReviewDetail; validationRunId: string; onApplied: () => void; targetLineItemId?: string | null; reviewActionType?: string | null }>) {
  // OP-CAP-15E — a CORRECT_LINE remediation deep-link preselects the line-item target here.
  const lineTargeted = reviewActionType === "CORRECT_LINE" && !!targetLineItemId;
  const [targetType, setTargetType] = useState<"FIELD" | "LINE_ITEM">(lineTargeted ? "LINE_ITEM" : "FIELD");
  const [targetId, setTargetId] = useState(lineTargeted ? targetLineItemId! : "");
  const [correctedValue, setCorrectedValue] = useState("");
  const [correctedQuantity, setCorrectedQuantity] = useState("");
  const [correctedUom, setCorrectedUom] = useState("");
  const [reason, setReason] = useState("");
  const [state, setState] = useState<ActionState>(IDLE);
  const submitting = state.status === "loading";

  function validate(): string | null {
    if (!targetId) return "Select a field or line item to correct.";
    if (reason.trim() === "") return "A correction reason is required.";
    if (targetType === "FIELD" && correctedValue.trim() === "") return "Enter a corrected value.";
    if (targetType === "LINE_ITEM") {
      if (correctedQuantity.trim() === "" && correctedUom.trim() === "") return "Enter a corrected quantity and/or UOM.";
      if (correctedQuantity.trim() !== "") {
        const qty = Number(correctedQuantity);
        if (!Number.isFinite(qty) || qty <= 0) return "Quantity must be a positive number.";
      }
    }
    return null;
  }

  async function submit() {
    const error = validate();
    if (error) {
      setState({ status: "error", message: error });
      return;
    }
    setState({ status: "loading", message: "Submitting correction…" });
    const result = await submitValidationReviewCorrection(validationRunId, {
      targetType,
      targetId,
      correctedValue: targetType === "FIELD" ? correctedValue.trim() : undefined,
      correctedQuantity: targetType === "LINE_ITEM" && correctedQuantity.trim() !== "" ? correctedQuantity.trim() : undefined,
      correctedUom: targetType === "LINE_ITEM" && correctedUom.trim() !== "" ? correctedUom.trim() : undefined,
      reason: reason.trim()
    });
    if (result.error || !result.data) {
      setState({ status: "error", message: result.error ?? "Correction was not accepted by the backend." });
      return;
    }
    setState({ status: "success", message: result.data.message });
    setCorrectedValue("");
    setCorrectedQuantity("");
    setCorrectedUom("");
    setReason("");
    onApplied();
  }

  return (
    <div className="action-block">
      <h3>Correct an extracted value</h3>
      <div className="form-row">
        <label>
          Target
          <select value={targetType} onChange={(e) => { setTargetType(e.target.value as "FIELD" | "LINE_ITEM"); setTargetId(""); }} disabled={submitting}>
            <option value="FIELD">Field</option>
            <option value="LINE_ITEM">Line item</option>
          </select>
        </label>
        <label>
          {targetType === "FIELD" ? "Field" : "Line item"}
          <select value={targetId} onChange={(e) => setTargetId(e.target.value)} disabled={submitting}>
            <option value="">Select…</option>
            {targetType === "FIELD"
              ? detail.fields.map((f) => <option key={f.fieldId} value={f.fieldId}>{f.fieldName}</option>)
              : detail.lineItems.map((l) => <option key={l.lineItemId} value={l.lineItemId}>#{l.lineNumber} {l.rawSku ?? ""}</option>)}
          </select>
        </label>
      </div>
      {targetType === "FIELD" ? (
        <label>
          Corrected value
          <input type="text" maxLength={MAX_VALUE} value={correctedValue} onChange={(e) => setCorrectedValue(e.target.value)} disabled={submitting} />
        </label>
      ) : (
        <div className="form-row">
          <label>
            Corrected quantity
            <input type="text" inputMode="decimal" value={correctedQuantity} onChange={(e) => setCorrectedQuantity(e.target.value)} disabled={submitting} />
          </label>
          <label>
            Corrected UOM
            <input type="text" maxLength={MAX_UOM} value={correctedUom} onChange={(e) => setCorrectedUom(e.target.value)} disabled={submitting} />
          </label>
        </div>
      )}
      <label>
        Reason
        <input type="text" maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} disabled={submitting} />
      </label>
      <div className="button-row">
        <button className="button" type="button" onClick={() => void submit()} disabled={submitting}>
          {submitting ? "Submitting…" : "Submit correction"}
        </button>
      </div>
      {state.message ? <p className={feedbackClass(state.status)}>{state.message}</p> : null}
    </div>
  );
}

function IssueResolutionControls({
  detail,
  validationRunId,
  onApplied,
  targetIssueId
}: Readonly<{ detail: ValidationReviewDetail; validationRunId: string; onApplied: () => void; targetIssueId?: string | null }>) {
  // OP-CAP-15E — a RESOLVE_ISSUE / VIEW_ISSUE remediation deep-link preselects the issue target here.
  const [issueId, setIssueId] = useState(targetIssueId ?? "");
  const [reason, setReason] = useState("");
  const [state, setState] = useState<ActionState>(IDLE);
  const submitting = state.status === "loading";

  async function resolve(resolution: IssueResolution) {
    if (!issueId) {
      setState({ status: "error", message: "Select an issue to act on." });
      return;
    }
    if (reason.trim() === "") {
      setState({ status: "error", message: "A reason is required to resolve an issue." });
      return;
    }
    setState({ status: "loading", message: `Marking issue ${resolution.toLowerCase()}…` });
    const result = await resolveValidationReviewIssue(validationRunId, issueId, { resolution, reason: reason.trim() });
    if (result.error || !result.data) {
      setState({ status: "error", message: result.error ?? "Resolution was not accepted by the backend." });
      return;
    }
    setState({ status: "success", message: result.data.message });
    setReason("");
    onApplied();
  }

  return (
    <div className="action-block">
      <h3>Resolve a validation issue</h3>
      {detail.issues.length === 0 ? (
        <p className="muted-copy">No validation issues to act on.</p>
      ) : (
        <>
          <label>
            Issue
            <select value={issueId} onChange={(e) => setIssueId(e.target.value)} disabled={submitting}>
              <option value="">Select…</option>
              {detail.issues.map((i) => (
                <option key={i.issueId} value={i.issueId}>{i.severity} · {i.code} · {i.status}</option>
              ))}
            </select>
          </label>
          <label>
            Reason
            <input type="text" maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} disabled={submitting} />
          </label>
          <div className="button-row">
            <button className="button" type="button" onClick={() => void resolve("RESOLVED")} disabled={submitting}>Resolve</button>
            <button className="button" type="button" onClick={() => void resolve("IGNORED")} disabled={submitting}>Ignore</button>
            <button className="button" type="button" onClick={() => void resolve("ESCALATED")} disabled={submitting}>Escalate</button>
          </div>
        </>
      )}
      {state.message ? <p className={feedbackClass(state.status)}>{state.message}</p> : null}
    </div>
  );
}

function ApprovalRequestControl({
  detail,
  validationRunId,
  onApplied,
  targetLineItemId,
  reviewActionType
}: Readonly<{ detail: ValidationReviewDetail; validationRunId: string; onApplied: () => void; targetLineItemId?: string | null; reviewActionType?: string | null }>) {
  // OP-CAP-15E — a REQUEST_APPROVAL remediation deep-link preselects the (optional) line target here.
  const approvalTargeted = reviewActionType === "REQUEST_APPROVAL" && !!targetLineItemId;
  const [lineItemId, setLineItemId] = useState(approvalTargeted ? targetLineItemId! : "");
  const [reason, setReason] = useState("");
  const [state, setState] = useState<ActionState>(IDLE);
  const submitting = state.status === "loading";

  async function submit() {
    if (reason.trim() === "") {
      setState({ status: "error", message: "A reason is required to request approval." });
      return;
    }
    setState({ status: "loading", message: "Requesting approval…" });
    const result = await requestValidationReviewApproval(validationRunId, {
      extractedLineItemId: lineItemId !== "" ? lineItemId : undefined,
      requirementType: "OPERATOR_CORRECTION_REVIEW",
      reason: reason.trim()
    });
    if (result.error || !result.data) {
      setState({ status: "error", message: result.error ?? "Approval request was not accepted by the backend." });
      return;
    }
    // Pending state is backend-confirmed (actionStatus from the created approval requirement).
    setState({ status: "success", message: `${result.data.message} (status: ${result.data.actionStatus})` });
    setReason("");
    onApplied();
  }

  return (
    <div className="action-block">
      <h3>Request approval for a risky review item</h3>
      <p className="muted-copy">Creates a pending approval request. Approve or reject decisions are not performed here.</p>
      <div className="form-row">
        <label>
          Line item (optional)
          <select value={lineItemId} onChange={(e) => setLineItemId(e.target.value)} disabled={submitting}>
            <option value="">Run-level</option>
            {detail.lineItems.map((l) => <option key={l.lineItemId} value={l.lineItemId}>#{l.lineNumber} {l.rawSku ?? ""}</option>)}
          </select>
        </label>
        <label>
          Reason
          <input type="text" maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} disabled={submitting} />
        </label>
      </div>
      <div className="button-row">
        <button className="button" type="button" onClick={() => void submit()} disabled={submitting}>
          {submitting ? "Requesting…" : "Request approval"}
        </button>
      </div>
      {state.message ? <p className={feedbackClass(state.status)}>{state.message}</p> : null}
    </div>
  );
}
