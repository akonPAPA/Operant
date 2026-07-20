"use client";

import { FormEvent, useRef, useState } from "react";

import {
  approveQuote,
  convertQuoteToInternalOrder,
  createDraftQuoteFromRfq,
  getQuoteApprovalState,
  QuoteApprovalCommandResponse,
  QuoteApprovalState,
  QuoteTransactionResponse,
  rejectQuote,
  requestQuoteChanges
} from "@/lib/quote-transaction-api";
import {
  mapOperatorActionError,
  type OperatorActionResult,
  useOperatorAction
} from "@/lib/operator-action-runtime";
import {
  idempotencyKeyForCreateDraftFromRfq,
  idempotencyKeyForQuoteApprovalAction
} from "@/lib/quote-mutation-idempotency";
import { BoundedUiError, boundedUiErrorMessage } from "@/lib/ui-error";

export type QuoteWorkspaceProps = Readonly<{
  /** Server-projected offer only — BFF/Core remain authoritative for mutations. */
  canPerformQuoteAction: boolean;
}>;

type MutationPhase =
  | "IDLE"
  | "SUBMITTING"
  | "SUCCEEDED"
  | "CONFLICT"
  | "VALIDATION_FAILED"
  | "ACCESS_DENIED"
  | "AUTH_REQUIRED"
  | "DEPENDENCY_UNAVAILABLE"
  | "CONTRACT_ERROR"
  | "UNKNOWN_SAFE_ERROR";

function phaseFromErrorCode(code: string, httpStatus?: number): MutationPhase {
  if (code === "CONFLICT") return "CONFLICT";
  if (code === "VALIDATION_FAILED") return "VALIDATION_FAILED";
  if (code === "PERMISSION_DENIED") {
    return httpStatus === 401 ? "AUTH_REQUIRED" : "ACCESS_DENIED";
  }
  if (httpStatus === 503 || httpStatus === 504) return "DEPENDENCY_UNAVAILABLE";
  if (httpStatus === 502) return "CONTRACT_ERROR";
  return "UNKNOWN_SAFE_ERROR";
}

export function QuoteWorkspace({ canPerformQuoteAction }: QuoteWorkspaceProps) {
  const [result, setResult] = useState<QuoteTransactionResponse | null>(null);
  const [approvalState, setApprovalState] = useState<QuoteApprovalState | null>(null);
  const [approvalResult, setApprovalResult] = useState<QuoteApprovalCommandResponse | null>(null);
  const [decisionReason, setDecisionReason] = useState("");
  const [message, setMessage] = useState("");
  const [messageKind, setMessageKind] = useState<"done" | "error">("done");
  const [phase, setPhase] = useState<MutationPhase>("IDLE");
  const mutationKeysRef = useRef<Map<string, string>>(new Map());

  const { execute, pending, disabled: actionDisabled } = useOperatorAction<
    QuoteTransactionResponse | QuoteApprovalCommandResponse
  >();

  async function runMutation<T>(
    actionKey: string,
    resolveIdempotencyKey: () => string,
    operation: (idempotencyKey: string) => Promise<T>,
    onSuccess: (data: T) => Promise<void> | void,
    doneMessage: string
  ) {
    if (!canPerformQuoteAction) {
      setMessageKind("error");
      setMessage("Quote mutations are not available for your session.");
      setPhase("ACCESS_DENIED");
      return;
    }

    let idempotencyKey = mutationKeysRef.current.get(actionKey);
    if (!idempotencyKey) {
      idempotencyKey = resolveIdempotencyKey();
      mutationKeysRef.current.set(actionKey, idempotencyKey);
    }

    setPhase("SUBMITTING");
    setMessage("");
    const runOperation = async (): Promise<
      OperatorActionResult<QuoteTransactionResponse | QuoteApprovalCommandResponse>
    > => {
      try {
        const data = (await operation(idempotencyKey)) as QuoteTransactionResponse | QuoteApprovalCommandResponse;
        return { ok: true as const, data, safeMessage: doneMessage };
      } catch (error) {
        const httpStatus = error instanceof BoundedUiError ? error.httpStatus : undefined;
        const { errorCode, safeMessage } = mapOperatorActionError(
          httpStatus ?? 500,
          boundedUiErrorMessage(error, "The action could not be completed. Please try again or contact support.")
        );
        return { ok: false as const, errorCode, safeMessage };
      }
    };

    const actionResult = await execute(runOperation);
    if (actionResult.ok) {
      mutationKeysRef.current.delete(actionKey);
      await onSuccess(actionResult.data as T);
      setMessageKind("done");
      setMessage(doneMessage);
      setPhase("SUCCEEDED");
      return;
    }

    setPhase(phaseFromErrorCode(actionResult.errorCode));
    setMessageKind("error");
    setMessage(actionResult.safeMessage);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canPerformQuoteAction) return;
    const form = new FormData(event.currentTarget);
    const rfqPayload = {
      customerExternalRef: String(form.get("customerExternalRef") || "CUST-001"),
      requestedLocation: String(form.get("requestedLocation") || "WH-ALM"),
      requestedDiscountPercent: Number(form.get("requestedDiscountPercent") || 0),
      requestedItems: [
        {
          rawSkuOrAlias: String(form.get("rawSkuOrAlias") || "PAD-OE-04465"),
          description: String(form.get("description") || "Original brake pads for Toyota Camry 2018"),
          quantity: Number(form.get("quantity") || 2),
          uom: String(form.get("uom") || "EA")
        }
      ]
    };
    const intentKey = idempotencyKeyForCreateDraftFromRfq(rfqPayload);
    await runMutation(
      intentKey,
      () => idempotencyKeyForCreateDraftFromRfq(rfqPayload),
      (idempotencyKey) =>
        createDraftQuoteFromRfq({
          ...rfqPayload,
          idempotencyKey
        }),
      async (response) => {
        setResult(response);
        setApprovalResult(null);
        setApprovalState(await getQuoteApprovalState(response.draftQuoteId));
      },
      "Draft quote created through the backend transaction service."
    );
  }

  async function runApprovalAction(action: "approve" | "reject" | "changes" | "convert") {
    if (!result || !canPerformQuoteAction) return;
    if ((action === "reject" || action === "changes") && !decisionReason.trim()) {
      setMessageKind("error");
      setMessage("Reason/comment is required for reject or request changes.");
      setPhase("VALIDATION_FAILED");
      return;
    }
    const intentKey = idempotencyKeyForQuoteApprovalAction({
      action,
      quoteId: result.draftQuoteId,
      reason: decisionReason,
      comment: decisionReason
    });
    await runMutation(
      intentKey,
      () =>
        idempotencyKeyForQuoteApprovalAction({
          action,
          quoteId: result.draftQuoteId,
          reason: decisionReason,
          comment: decisionReason
        }),
      (idempotencyKey) => {
        const body = { reason: decisionReason, comment: decisionReason, idempotencyKey };
        return action === "approve"
          ? approveQuote(result.draftQuoteId, body)
          : action === "reject"
            ? rejectQuote(result.draftQuoteId, body)
            : action === "changes"
              ? requestQuoteChanges(result.draftQuoteId, body)
              : convertQuoteToInternalOrder(result.draftQuoteId, body);
      },
      async (response) => {
        const approvalResponse = response as QuoteApprovalCommandResponse;
        setApprovalResult(approvalResponse);
        setApprovalState(await getQuoteApprovalState(result.draftQuoteId));
        setResult({
          ...result,
          status: approvalResponse.newStatus,
          approvalRequired: approvalResponse.approvalRequired,
          approvalReasons: approvalResponse.approvalReasons
        });
      },
      "Quote approval action completed. External ERP write was not executed."
    );
  }

  const busy = pending || phase === "SUBMITTING";
  const statusRegionId = "quote-workspace-status";

  return (
    <div className="stack">
      {!canPerformQuoteAction ? (
        <section className="panel">
          <h2>Read-only quote workspace</h2>
          <p>
            Your session can open this workspace but quote mutations are not offered. Approval and draft
            creation controls are withheld until the backend projects quote action capability.
          </p>
        </section>
      ) : null}

      {canPerformQuoteAction ? (
        <section className="panel">
          <h2>RFQ to Draft Quote</h2>
          <p className="risk-note">
            Demo path: Steppe Logistics requests out-of-stock OE brake pads. Operant validates the draft,
            shows substitute/approval context, and keeps externalExecution=DISABLED.
          </p>
          <form className="upload-form" onSubmit={submit}>
            <label>
              <span>Customer external ref</span>
              <input name="customerExternalRef" defaultValue="CUST-001" />
            </label>
            <label>
              <span>SKU or alias</span>
              <input name="rawSkuOrAlias" defaultValue="PAD-OE-04465" />
            </label>
            <label>
              <span>Description</span>
              <input name="description" defaultValue="Original brake pads for Toyota Camry 2018" />
            </label>
            <label>
              <span>Quantity</span>
              <input name="quantity" type="number" min="1" defaultValue="2" />
            </label>
            <label>
              <span>UOM</span>
              <input name="uom" defaultValue="EA" />
            </label>
            <label>
              <span>Location</span>
              <input name="requestedLocation" defaultValue="WH-ALM" />
            </label>
            <label>
              <span>Discount percent</span>
              <input name="requestedDiscountPercent" type="number" min="0" step="0.01" defaultValue="0" />
            </label>
            <button
              className="button"
              disabled={busy || actionDisabled}
              type="submit"
              aria-busy={busy}
            >
              {busy ? "Submitting..." : "Create Draft Quote"}
            </button>
          </form>
        </section>
      ) : null}

      <div
        id={statusRegionId}
        role="status"
        aria-live="polite"
        aria-atomic="true"
        className={message ? (messageKind === "done" ? "form-message done" : "form-message error") : undefined}
      >
        {message || null}
      </div>

      {result ? (
        <>
          <section className="panel">
            <h2>Quote</h2>
            <p>Quote: {result.draftQuoteId}</p>
            <p>Status: {result.status}</p>
            <p>Customer: {result.resolvedCustomer?.displayName ?? "Unresolved"}</p>
            <p>Approval: {result.approvalRequired ? result.approvalReasons.join(", ") : "Not required"}</p>
            <p>External ERP write: disabled / not executed</p>
          </section>
          <section className="panel">
            <h2>Approval</h2>
            <p>Status: {approvalState?.status ?? result.status}</p>
            <p>Approval required: {(approvalState?.approvalRequired ?? result.approvalRequired) ? "Yes" : "No"}</p>
            <p>
              Approval reasons:{" "}
              {(approvalState?.approvalReasons.length ? approvalState.approvalReasons : result.approvalReasons).join(
                ", "
              ) || "None"}
            </p>
            <p>
              Blocking issues:{" "}
              {(approvalState?.blockingIssues ?? result.validationIssues.filter((issue) => issue.blocking))
                .map((issue) => issue.issueCode)
                .join(", ") || "None"}
            </p>
            {canPerformQuoteAction ? (
              <>
                <label>
                  <span>Reason/comment</span>
                  <input
                    aria-label="Approval decision reason"
                    value={decisionReason}
                    onChange={(event) => setDecisionReason(event.target.value)}
                    placeholder="Required for reject or request changes"
                  />
                </label>
                <div className="action-row">
                  <button
                    className="button"
                    disabled={
                      busy ||
                      actionDisabled ||
                      Boolean(
                        (approvalState?.blockingIssues ?? result.validationIssues).some((issue) => issue.blocking)
                      ) ||
                      result.status === "CONVERTED_TO_INTERNAL_ORDER"
                    }
                    type="button"
                    aria-busy={busy}
                    onClick={() => void runApprovalAction("approve")}
                  >
                    Approve
                  </button>
                  <button
                    className="button secondary-button"
                    disabled={
                      busy ||
                      actionDisabled ||
                      !decisionReason.trim() ||
                      result.status === "CONVERTED_TO_INTERNAL_ORDER"
                    }
                    type="button"
                    onClick={() => void runApprovalAction("reject")}
                  >
                    Reject
                  </button>
                  <button
                    className="button secondary-button"
                    disabled={
                      busy ||
                      actionDisabled ||
                      !decisionReason.trim() ||
                      result.status === "CONVERTED_TO_INTERNAL_ORDER"
                    }
                    type="button"
                    onClick={() => void runApprovalAction("changes")}
                  >
                    Request changes
                  </button>
                  {result.status === "APPROVED" ? (
                    <button
                      className="button"
                      disabled={busy || actionDisabled}
                      type="button"
                      onClick={() => void runApprovalAction("convert")}
                    >
                      Convert to internal order
                    </button>
                  ) : null}
                </div>
              </>
            ) : (
              <p className="muted-copy">Approval actions are not offered for this session.</p>
            )}
            {approvalResult ? (
              <div className="result-panel">
                <p>Decision: {approvalResult.approvalDecision}</p>
                <p>Previous status: {approvalResult.previousStatus}</p>
                <p>New status: {approvalResult.newStatus}</p>
                <p>Internal draft order boundary: {approvalResult.internalDraftOrderId ?? "Not created"}</p>
                <p>ChangeRequest: {approvalResult.changeRequestId ?? "Not created"}</p>
                <p>
                  External execution:{" "}
                  {approvalResult.externalExecutionEnabled ? "Enabled" : "Disabled (no external write)"}
                </p>
              </div>
            ) : null}
          </section>
          <section className="panel table-panel">
            <h2>Lines</h2>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Resolved</th>
                  <th>Qty</th>
                  <th>Price</th>
                  <th>Margin</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {result.lines.map((line) => (
                  <tr key={line.id}>
                    <td>{line.rawSkuOrAlias}</td>
                    <td>{line.productName ?? "Unresolved"}</td>
                    <td>
                      {line.quantity} {line.uom}
                    </td>
                    <td>{line.unitPrice ?? "n/a"}</td>
                    <td>{line.marginPercent ?? "n/a"}</td>
                    <td>{line.validationStatus}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="panel table-panel">
            <h2>Validation Issues</h2>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Severity</th>
                  <th>Blocking</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                {result.validationIssues.length ? (
                  result.validationIssues.map((issue) => (
                    <tr key={issue.id}>
                      <td>{issue.issueCode}</td>
                      <td>{issue.severity}</td>
                      <td>{issue.blocking ? "Yes" : "No"}</td>
                      <td>{issue.message}</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={4}>No validation issues.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </section>
          <section className="panel table-panel">
            <h2>Substitutes</h2>
            <table className="data-table">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Risk</th>
                  <th>Stock</th>
                  <th>Approval</th>
                </tr>
              </thead>
              <tbody>
                {result.substituteCandidates.length ? (
                  result.substituteCandidates.map((candidate) => (
                    <tr key={`${candidate.lineId}-${candidate.productId}`}>
                      <td>{candidate.sku}</td>
                      <td>{candidate.riskLevel}</td>
                      <td>{candidate.stockStatus}</td>
                      <td>{candidate.requiresApproval || candidate.blocked ? "Required" : "Not required"}</td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={4}>No substitute candidates.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </section>
        </>
      ) : null}
    </div>
  );
}
