"use client";

import { useState } from "react";
import {
  getDraftOrderReview,
  getDraftQuoteReview,
  markDraftOrderReady,
  markDraftQuoteReady,
  updateDraftOrderLine,
  updateDraftQuoteLine,
  type DraftLineCorrection,
  type DraftReviewDetail,
  type DraftReviewLine
} from "@/lib/draft-review-api";

// Exact backend status tokens — do not rename.
const READY_STATUS = "WAITING_APPROVAL";
const LOCKED_STATUSES = ["APPROVED_INTERNAL", "APPROVED", "REJECTED", "CANCELLED"];
const MAX_UOM_LENGTH = 16;
const MAX_TEXT_LENGTH = 512;

type DraftType = "QUOTE" | "ORDER";
type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };

type EditForm = {
  description: string;
  quantity: string;
  uom: string;
  unitPrice: string;
  productId: string;
  correctionReason: string;
};

function emptyForm(): EditForm {
  return { description: "", quantity: "", uom: "", unitPrice: "", productId: "", correctionReason: "" };
}

function formFromLine(line: DraftReviewLine): EditForm {
  return {
    description: line.description ?? "",
    quantity: line.quantity !== undefined && line.quantity !== null ? String(line.quantity) : "",
    uom: line.uom ?? "",
    unitPrice: line.unitPrice !== undefined && line.unitPrice !== null ? String(line.unitPrice) : "",
    productId: "",
    correctionReason: ""
  };
}

function formatTimestamp(ts?: string): string {
  if (!ts) return "n/a";
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return ts;
  }
}

function show(value?: number | string | null): string {
  return value === undefined || value === null || value === "" ? "—" : String(value);
}

// Pure client-side guard mirroring the 09B backend rules. Backend remains authoritative.
function validateForm(form: EditForm): string | null {
  const hasField =
    form.description !== "" || form.quantity !== "" || form.uom !== "" || form.unitPrice !== "" || form.productId !== "";
  if (!hasField) return "Enter at least one field to correct.";
  if (form.quantity !== "") {
    const qty = Number(form.quantity);
    if (!Number.isFinite(qty) || qty <= 0) return "Quantity must be a positive number.";
  }
  if (form.unitPrice !== "") {
    const price = Number(form.unitPrice);
    if (!Number.isFinite(price) || price < 0) return "Unit price must not be negative.";
  }
  if (form.uom !== "" && form.uom.trim().length > MAX_UOM_LENGTH) return `UOM must be at most ${MAX_UOM_LENGTH} characters.`;
  if (form.description.length > MAX_TEXT_LENGTH) return `Description must be at most ${MAX_TEXT_LENGTH} characters.`;
  if (form.correctionReason.length > MAX_TEXT_LENGTH) return `Correction reason must be at most ${MAX_TEXT_LENGTH} characters.`;
  return null;
}

function correctionFromForm(form: EditForm): DraftLineCorrection {
  return {
    description: form.description !== "" ? form.description : undefined,
    quantity: form.quantity !== "" ? form.quantity : undefined,
    uom: form.uom !== "" ? form.uom : undefined,
    unitPrice: form.unitPrice !== "" ? form.unitPrice : undefined,
    productId: form.productId !== "" ? form.productId : undefined,
    correctionReason: form.correctionReason !== "" ? form.correctionReason : undefined
  };
}

export function DraftReviewWorkspace({
  draftType,
  initialDetail,
  initialError
}: Readonly<{
  draftType: DraftType;
  initialDetail: DraftReviewDetail | null;
  initialError?: string;
}>) {
  const [detail, setDetail] = useState<DraftReviewDetail | null>(initialDetail);
  const [editingLineId, setEditingLineId] = useState<string | null>(null);
  const [form, setForm] = useState<EditForm>(emptyForm());
  const [saving, setSaving] = useState(false);
  const [markingReady, setMarkingReady] = useState(false);
  const [action, setAction] = useState<ActionState>({
    status: initialError ? "error" : "idle",
    message: initialError ?? ""
  });

  const isOrder = draftType === "ORDER";
  const typeLabel = isOrder ? "Order" : "Quote";

  if (!detail) {
    return (
      <section className="panel">
        <h2>Draft {typeLabel.toLowerCase()} unavailable</h2>
        <p className="form-message error">{initialError ?? "Draft could not be loaded."}</p>
      </section>
    );
  }

  const status = detail.status;
  const isReady = status === READY_STATUS;
  const isLocked = LOCKED_STATUSES.includes(status);
  const canMarkReady = !isReady && !isLocked;
  const busy = saving || markingReady;

  function startEdit(line: DraftReviewLine) {
    setEditingLineId(line.lineId);
    setForm(formFromLine(line));
    setAction({ status: "idle", message: "" });
  }

  function cancelEdit() {
    setEditingLineId(null);
    setForm(emptyForm());
  }

  async function submitCorrection(lineId: string) {
    const validationError = validateForm(form);
    if (validationError) {
      setAction({ status: "error", message: validationError });
      return;
    }
    setSaving(true);
    setAction({ status: "loading", message: "Saving correction through the permissioned backend command…" });
    const payload = correctionFromForm(form);
    const result = isOrder
      ? await updateDraftOrderLine(detail!.draftId, lineId, payload)
      : await updateDraftQuoteLine(detail!.draftId, lineId, payload);
    setSaving(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Correction was not accepted by the backend." });
      return;
    }
    // Backend returns the full updated draft — replace state, never insert client-side rows.
    setDetail(result.data);
    setEditingLineId(null);
    setForm(emptyForm());
    setAction({ status: "success", message: "Line corrected. Draft returned to internal review." });
  }

  async function submitMarkReady() {
    setMarkingReady(true);
    setAction({ status: "loading", message: "Marking draft ready for internal approval…" });
    const result = isOrder
      ? await markDraftOrderReady(detail!.draftId)
      : await markDraftQuoteReady(detail!.draftId);
    setMarkingReady(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Mark-ready was not accepted by the backend." });
      return;
    }
    setDetail(result.data);
    setAction({ status: "success", message: "Draft marked ready for internal approval." });
  }

  async function refresh() {
    setAction({ status: "loading", message: "Refreshing draft…" });
    const result = isOrder ? await getDraftOrderReview(detail!.draftId) : await getDraftQuoteReview(detail!.draftId);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Could not refresh draft." });
      return;
    }
    setDetail(result.data);
    setAction({ status: "idle", message: "" });
  }

  return (
    <div className="review-workspace">
      {/* Internal-only banner */}
      <section className="panel">
        <div className="button-row">
          <span className="status-pill">Internal draft review only</span>
          <span className="status-pill">External execution: {detail.externalExecution}</span>
        </div>
        <p className="muted-copy">
          This screen reviews an internal draft {typeLabel.toLowerCase()} prepared from a validation review case.
          It does not approve a final {typeLabel.toLowerCase()}, reserve inventory, or write to any ERP/1C/accounting/connector system.
        </p>
      </section>

      {/* Feedback banner */}
      {action.message ? (
        <section className="panel">
          <p className={`form-message ${action.status === "error" ? "error" : action.status === "success" ? "done" : ""}`}>
            {action.message}
          </p>
        </section>
      ) : null}

      {/* Header */}
      <section className="panel action-panel">
        <h2>Draft {typeLabel} review</h2>
        <dl className="detail-list">
          <div><dt>Draft type</dt><dd>{typeLabel}</dd></div>
          <div><dt>Draft id</dt><dd>{detail.draftId}</dd></div>
          {detail.sourceReviewCaseId ? (
            <div><dt>Source review case</dt><dd>{detail.sourceReviewCaseId}</dd></div>
          ) : null}
          {detail.sourceValidationRunId ? (
            <div><dt>Source validation run</dt><dd>{detail.sourceValidationRunId}</dd></div>
          ) : null}
          <div><dt>Status</dt><dd><span className="status-pill">{status}</span></dd></div>
          {detail.customerDisplayName ? (
            <div><dt>Customer</dt><dd>{detail.customerDisplayName}</dd></div>
          ) : detail.customerAccountId ? (
            <div><dt>Customer account</dt><dd>{detail.customerAccountId}</dd></div>
          ) : null}
          {detail.currency ? <div><dt>Currency</dt><dd>{detail.currency}</dd></div> : null}
          <div><dt>Subtotal</dt><dd>{show(detail.subtotalAmount)}</dd></div>
          <div><dt>Total</dt><dd>{show(detail.totalAmount)}</dd></div>
          <div><dt>Line count</dt><dd>{detail.lineCount}</dd></div>
          <div><dt>Created</dt><dd>{formatTimestamp(detail.createdAt)}</dd></div>
          <div><dt>External execution</dt><dd>{detail.externalExecution}</dd></div>
        </dl>

        <div className="button-row">
          <button className="button" type="button" disabled={busy} onClick={() => void refresh()}>
            Refresh
          </button>
          {isReady ? (
            <span className="muted-copy">Already marked ready for internal approval ({READY_STATUS}).</span>
          ) : isLocked ? (
            <span className="muted-copy">Draft is in a locked status ({status}); mark-ready is unavailable.</span>
          ) : (
            <button className="button" type="button" disabled={busy || !canMarkReady} onClick={() => void submitMarkReady()}>
              {markingReady ? "Marking ready…" : "Mark ready for internal approval"}
            </button>
          )}
        </div>
      </section>

      {/* Line table */}
      <section className="panel table-panel">
        <h2>Draft lines</h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              {!isOrder ? <th>SKU</th> : null}
              {!isOrder ? <th>Product</th> : null}
              <th>Description</th>
              <th>Qty</th>
              <th>UOM</th>
              <th>Unit price</th>
              <th>Discount %</th>
              <th>Margin %</th>
              <th>Line total</th>
              <th>Status</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.length === 0 ? (
              <tr>
                <td colSpan={isOrder ? 9 : 11}>No lines on this draft yet.</td>
              </tr>
            ) : (
              detail.lines.map((line) => (
                <tr key={line.lineId} className={editingLineId === line.lineId ? "selected-row" : ""}>
                  <td>{line.lineNumber}</td>
                  {!isOrder ? <td>{line.normalizedSku ?? line.rawSku ?? "—"}</td> : null}
                  {!isOrder ? <td>{line.productName ?? "—"}</td> : null}
                  <td>{line.description ?? "—"}</td>
                  <td>{show(line.quantity)}</td>
                  <td>{line.uom ?? "—"}</td>
                  <td>{show(line.unitPrice)}</td>
                  <td>{show(line.discountPercent)}</td>
                  <td>{show(line.marginPercent)}</td>
                  <td>{show(line.lineTotal)}</td>
                  <td>
                    <span className="status-pill">{line.status}</span>
                    {line.validationStatus && line.validationStatus !== line.status ? (
                      <span className="muted-copy"> / {line.validationStatus}</span>
                    ) : null}
                  </td>
                  <td>
                    <button
                      className="button"
                      type="button"
                      disabled={busy || isLocked}
                      onClick={() => startEdit(line)}
                    >
                      Edit
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
        {isLocked ? (
          <p className="muted-copy">Lines cannot be corrected while the draft is in a locked status ({status}).</p>
        ) : null}
      </section>

      {/* Correction form */}
      {editingLineId ? (
        <section className="panel action-panel">
          <h2>Correct line</h2>
          <p className="muted-copy">
            Only bounded review fields can be corrected. The backend re-validates tenant scope, line ownership,
            and draft status on every request.
          </p>
          <div className="control-grid">
            <label className="field-label" htmlFor="dr-description">Description</label>
            <input
              id="dr-description"
              className="form-input"
              type="text"
              maxLength={MAX_TEXT_LENGTH}
              value={form.description}
              disabled={busy}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />

            <label className="field-label" htmlFor="dr-quantity">Quantity</label>
            <input
              id="dr-quantity"
              className="form-input"
              type="number"
              min="0"
              step="any"
              value={form.quantity}
              disabled={busy}
              onChange={(e) => setForm({ ...form, quantity: e.target.value })}
            />

            <label className="field-label" htmlFor="dr-uom">UOM</label>
            <input
              id="dr-uom"
              className="form-input"
              type="text"
              maxLength={MAX_UOM_LENGTH}
              value={form.uom}
              disabled={busy}
              onChange={(e) => setForm({ ...form, uom: e.target.value })}
            />

            <label className="field-label" htmlFor="dr-unit-price">Unit price</label>
            <input
              id="dr-unit-price"
              className="form-input"
              type="number"
              min="0"
              step="any"
              value={form.unitPrice}
              disabled={busy}
              onChange={(e) => setForm({ ...form, unitPrice: e.target.value })}
            />

            <label className="field-label" htmlFor="dr-product-id">Product id (optional)</label>
            <input
              id="dr-product-id"
              className="form-input"
              type="text"
              placeholder="existing tenant product id"
              value={form.productId}
              disabled={busy}
              onChange={(e) => setForm({ ...form, productId: e.target.value })}
            />

            <label className="field-label" htmlFor="dr-reason">Correction reason (optional)</label>
            <input
              id="dr-reason"
              className="form-input"
              type="text"
              maxLength={MAX_TEXT_LENGTH}
              value={form.correctionReason}
              disabled={busy}
              onChange={(e) => setForm({ ...form, correctionReason: e.target.value })}
            />
          </div>
          <div className="button-row">
            <button className="button" type="button" disabled={busy} onClick={() => void submitCorrection(editingLineId)}>
              {saving ? "Saving…" : "Save correction"}
            </button>
            <button className="button" type="button" disabled={busy} onClick={cancelEdit}>
              Cancel
            </button>
          </div>
        </section>
      ) : null}

      <p className="risk-note">
        Draft review corrections and mark-ready use the permissioned backend command path (REVIEW_ACTION).
        Final {typeLabel.toLowerCase()} approval, ERP/1C and accounting/connector execution, invoicing, and external
        synchronization are all out of scope for this workspace.
      </p>
    </div>
  );
}
