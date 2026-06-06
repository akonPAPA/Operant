"use client";

import { useMemo, useState } from "react";

import {
  acknowledgeReviewIssue,
  approveReviewApproval,
  approveValidationReviewCase,
  correctReviewQuantity,
  correctReviewUom,
  getDraftPreview,
  getValidationReviewCase,
  mapReviewProduct,
  overrideReviewIssue,
  prepareDraftOrder,
  prepareDraftQuote,
  rejectReviewApproval,
  rejectReviewSubstitute,
  rejectValidationReviewCase,
  selectReviewSubstitute,
  type DraftPreview,
  type ValidationReviewCase,
  type ValidationRunChecks
} from "@/lib/validation-review-api";
import { Timeline } from "./timeline";

type ActionState = {
  status: "idle" | "loading" | "success" | "error";
  message: string;
};
type ReviewActionResult = Promise<{ data: unknown; error?: string }>;
type RunReviewAction = (label: string, request: () => ReviewActionResult, shouldRefresh?: boolean) => Promise<void>;

export function ValidationReviewWorkspace({ initialCase, checks, initialPreview }: Readonly<{ initialCase: ValidationReviewCase; checks: ValidationRunChecks; initialPreview?: DraftPreview }>) {
  const [reviewCase, setReviewCase] = useState(initialCase);
  const [draftPreview, setDraftPreview] = useState(initialPreview);
  const [action, setAction] = useState<ActionState>({ status: "idle", message: "" });
  const [uomCorrections, setUomCorrections] = useState<Record<string, string>>({});
  const [quantityCorrections, setQuantityCorrections] = useState<Record<string, string>>({});
  const [productMappings, setProductMappings] = useState<Record<string, string>>({});
  const [approvalReasons, setApprovalReasons] = useState<Record<string, string>>({});
  const [overrideIssueId, setOverrideIssueId] = useState("");
  const [overrideReason, setOverrideReason] = useState("");

  const risks = useMemo(() => visibleRisks(reviewCase, checks), [reviewCase, checks]);
  const readiness = reviewCase.readiness;
  const backendBlockers = readiness?.blockingReasons ?? reviewCase.blockingReasons ?? [];
  const canPrepare = (readiness?.draftPreparationAllowed ?? reviewCase.draftPreparationAllowed) === true && backendBlockers.length === 0;

  async function refresh() {
    const result = await getValidationReviewCase(reviewCase.reviewCase.id);
    if (!result.error && result.data) {
      setReviewCase(result.data);
    }
    const preview = await getDraftPreview(reviewCase.reviewCase.id);
    if (!preview.error && preview.data) {
      setDraftPreview(preview.data);
    }
  }

  async function runAction(label: string, request: () => Promise<{ data: unknown; error?: string }>, shouldRefresh = true) {
    setAction({ status: "loading", message: `${label} in progress...` });
    const result = await request();
    if (result.error) {
      setAction({ status: "error", message: result.error });
      return;
    }
    setAction({ status: "success", message: `${label} completed.` });
    if (shouldRefresh) {
      await refresh();
    }
  }

  const issues = reviewCase.issueGroups.flatMap((group) => group.issues);

  return (
    <div className="review-workspace">
      <ReviewHeader reviewCase={reviewCase} />
      <OperatorActionsPanel action={action} backendBlockers={backendBlockers} canPrepare={canPrepare} reviewCase={reviewCase} hardBlockers={risks.hardBlockers} runAction={runAction} />
      <DraftReadinessPanel backendBlockers={backendBlockers} canPrepare={canPrepare} readiness={readiness} reviewCase={reviewCase} />
      <SafetyFlagsPanel risks={risks} />
      <DraftPreviewPanel draftPreview={draftPreview} />
      <LineItemValidationTable checks={checks} productMappings={productMappings} quantityCorrections={quantityCorrections} reviewCase={reviewCase} runAction={runAction} setProductMappings={setProductMappings} setQuantityCorrections={setQuantityCorrections} setUomCorrections={setUomCorrections} uomCorrections={uomCorrections} />

      <div className="page-grid">
        <ApprovalRequirementsPanel approvalReasons={approvalReasons} reviewCase={reviewCase} runAction={runAction} setApprovalReasons={setApprovalReasons} />
        <SubstituteCandidatesPanel reviewCase={reviewCase} runAction={runAction} />
        <ValidationIssuesPanel issues={issues} reviewCase={reviewCase} runAction={runAction} setOverrideIssueId={setOverrideIssueId} />
      </div>

      <OverrideReasonPanel overrideIssueId={overrideIssueId} overrideReason={overrideReason} reviewCaseId={reviewCase.reviewCase.id} runAction={runAction} setOverrideIssueId={setOverrideIssueId} setOverrideReason={setOverrideReason} />
      <TimelinePanels reviewCase={reviewCase} />
    </div>
  );
}

function ReviewHeader({ reviewCase }: Readonly<{ reviewCase: ValidationReviewCase }>) {
  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <h2>{reviewCase.reviewCase.caseNumber}</h2>
          <p>Review case {reviewCase.reviewCase.id}</p>
        </div>
        <span className={`status-pill ${reviewCase.reviewCase.severity === "CRITICAL" || reviewCase.reviewCase.severity === "ERROR" ? "warning" : ""}`}>
          {reviewCase.reviewCase.status}
        </span>
      </div>
      <dl className="detail-list">
        <div><dt>Extraction</dt><dd>{reviewCase.extraction.id}</dd></div>
        <div><dt>Source</dt><dd>{reviewCase.extraction.sourceType} / {reviewCase.extraction.sourceId}</dd></div>
        <div><dt>Intent</dt><dd>{reviewCase.extraction.detectedIntent}</dd></div>
        <div><dt>Validation</dt><dd>{reviewCase.validation.overallStatus} ({reviewCase.validation.riskLevel})</dd></div>
        <div><dt>Created</dt><dd>{formatDate(reviewCase.reviewCase.createdAt)}</dd></div>
      </dl>
    </section>
  );
}

function OperatorActionsPanel({ action, backendBlockers, canPrepare, hardBlockers, reviewCase, runAction }: Readonly<{ action: ActionState; backendBlockers: NonNullable<ValidationReviewCase["blockingReasons"]>; canPrepare: boolean; hardBlockers: string[]; reviewCase: ValidationReviewCase; runAction: RunReviewAction }>) {
  return (
    <section className="panel action-panel">
      <h2>Operator Actions</h2>
      <div className="button-row">
        <button className="button" disabled={action.status === "loading"} type="button" onClick={() => runAction("Approve review case", () => approveValidationReviewCase(reviewCase.reviewCase.id))}>Approve</button>
        <button className="button secondary-button" disabled={action.status === "loading"} type="button" onClick={() => runAction("Reject review case", () => rejectValidationReviewCase(reviewCase.reviewCase.id))}>Reject</button>
        <button className="button" disabled={action.status === "loading" || !canPrepare} title={!canPrepare ? blockerTitle(reviewCase, hardBlockers) : undefined} type="button" onClick={() => runAction("Prepare draft quote", () => prepareDraftQuote(reviewCase.reviewCase.id), false)}>Prepare Draft Quote</button>
        <button className="button" disabled={action.status === "loading" || !canPrepare} title={!canPrepare ? blockerTitle(reviewCase, hardBlockers) : undefined} type="button" onClick={() => runAction("Prepare draft order", () => prepareDraftOrder(reviewCase.reviewCase.id), false)}>Prepare Draft Order</button>
      </div>
      {action.message ? <p className={`form-message ${action.status === "error" ? "error" : action.status === "success" ? "done" : ""}`}>{action.message}</p> : null}
      {backendBlockers.length ? <BlockingReasonList reasons={backendBlockers} /> : null}
      <p className="risk-note">Draft preparation is internal only. Backend validation/review gates remain authoritative and may reject an action even when the button is enabled.</p>
    </section>
  );
}

function DraftReadinessPanel({ backendBlockers, canPrepare, readiness, reviewCase }: Readonly<{ backendBlockers: NonNullable<ValidationReviewCase["blockingReasons"]>; canPrepare: boolean; readiness: ValidationReviewCase["readiness"]; reviewCase: ValidationReviewCase }>) {
  return (
    <section className="panel">
      <h2>Draft Readiness</h2>
      <div className="tag-row">
        <span className={`status-pill ${canPrepare ? "" : "warning"}`}>{readiness?.readinessStatus ?? (canPrepare ? "READY" : "BLOCKED")}</span>
        {(readiness?.pendingApprovals ?? reviewCase.pendingApprovals ?? []).length ? <span className="status-pill warning">pending approval</span> : null}
        {(readiness?.rejectedApprovals ?? reviewCase.rejectedApprovals ?? []).length ? <span className="status-pill warning">rejected approval</span> : null}
      </div>
      {backendBlockers.length ? <BlockingReasonList keyPrefix="readiness" reasons={backendBlockers} /> : <p className="form-message done">Backend readiness evaluator allows internal draft preparation.</p>}
      {readiness?.nextRequiredActions?.length ? <ReviewList empty="" items={readiness.nextRequiredActions.map((item) => `Next action: ${item}`)} /> : null}
    </section>
  );
}

function BlockingReasonList({ keyPrefix = "blocker", reasons }: Readonly<{ keyPrefix?: string; reasons: NonNullable<ValidationReviewCase["blockingReasons"]> }>) {
  return (
    <ul className="review-list">
      {reasons.map((reason) => <li className="risk-cell" key={`${keyPrefix}-${reason.issueCode}-${reason.reason}`}>{reason.issueCode} - {reason.severity ?? ""} - {reason.reason} - {reason.suggestedCorrectionAction}</li>)}
    </ul>
  );
}

function SafetyFlagsPanel({ risks }: Readonly<{ risks: ReturnType<typeof visibleRisks> }>) {
  if (risks.all.length === 0) {
    return null;
  }
  return (
    <section className="panel">
      <h2>Safety Flags</h2>
      {risks.hardBlockers.includes("BLOCKED_SUBSTITUTE") ? <p className="risk-note">Blocked substitute warning: draft preparation stays guarded and the backend remains authoritative.</p> : null}
      <div className="tag-row">
        {risks.all.map((risk) => <span className="status-pill warning" key={risk}>{risk}</span>)}
      </div>
    </section>
  );
}

function DraftPreviewPanel({ draftPreview }: Readonly<{ draftPreview?: DraftPreview }>) {
  return (
    <section className="panel table-panel">
      <h2>Draft Quote Preview</h2>
      {draftPreview?.blockingReasons?.length ? <BlockingReasonList keyPrefix="preview" reasons={draftPreview.blockingReasons} /> : <p className="form-message done">Preview is preparable according to backend review gates.</p>}
      <table className="data-table">
        <thead>
          <tr><th>Line</th><th>Product</th><th>Substitute</th><th>Qty/UOM</th><th>Price</th><th>Stock</th><th>Margin</th><th>Status</th></tr>
        </thead>
        <tbody>
          {(draftPreview?.lines ?? []).map((line) => (
            <tr key={line.extractedLineItemId}>
              <td>{line.lineNumber}</td>
              <td>{line.productSku ?? line.rawSku ?? "Unmapped"}<br /><span className="muted-copy">{line.productName ?? line.description ?? ""}</span></td>
              <td>{line.substituteSku ?? "None"}<br /><span className="muted-copy">{line.substituteName ?? ""}</span></td>
              <td>{line.quantity ?? "n/a"} {line.uom ?? ""}</td>
              <td>{line.unitPrice ?? "n/a"} {line.currency ?? ""}</td>
              <td className={line.stockStatus !== "AVAILABLE" && line.stockStatus !== "NOT_CHECKED" ? "risk-cell" : ""}>{line.stockStatus}</td>
              <td className={line.marginStatus?.includes("BELOW") ? "risk-cell" : ""}>{line.marginPercent ?? "n/a"}<br /><span className="muted-copy">{line.marginStatus}</span></td>
              <td>{line.validationStatus}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {draftPreview?.readiness?.blockingReasons?.length ? <p className="risk-note">Preview readiness matches the backend preparation gate: {draftPreview.readiness.readinessStatus}.</p> : null}
      <p className="risk-note">Preview is internal only. External execution and ERP writes are disabled.</p>
    </section>
  );
}

function LineItemValidationTable({ checks, productMappings, quantityCorrections, reviewCase, runAction, setProductMappings, setQuantityCorrections, setUomCorrections, uomCorrections }: Readonly<{ checks: ValidationRunChecks; productMappings: Record<string, string>; quantityCorrections: Record<string, string>; reviewCase: ValidationReviewCase; runAction: RunReviewAction; setProductMappings: (value: Record<string, string>) => void; setQuantityCorrections: (value: Record<string, string>) => void; setUomCorrections: (value: Record<string, string>) => void; uomCorrections: Record<string, string> }>) {
  return (
    <section className="panel table-panel">
      <h2>Line Item Validation Results</h2>
      <table className="data-table">
        <thead>
          <tr><th>Line</th><th>Raw item</th><th>Product match</th><th>UOM</th><th>Stock</th><th>Price</th><th>Discount</th><th>Margin</th><th>Confidence</th></tr>
        </thead>
        <tbody>
          {reviewCase.lineItems.map((line) => <LineValidationRow checks={checks} key={line.id} line={line} productMapping={productMappings[line.id] ?? ""} quantityCorrection={quantityCorrections[line.id] ?? ""} reviewCase={reviewCase} runAction={runAction} setProductMappings={setProductMappings} setQuantityCorrections={setQuantityCorrections} setUomCorrections={setUomCorrections} uomCorrection={uomCorrections[line.id] ?? ""} productMappings={productMappings} quantityCorrections={quantityCorrections} uomCorrections={uomCorrections} />)}
        </tbody>
      </table>
    </section>
  );
}

function LineValidationRow({ checks, line, productMapping, productMappings, quantityCorrection, quantityCorrections, reviewCase, runAction, setProductMappings, setQuantityCorrections, setUomCorrections, uomCorrection, uomCorrections }: Readonly<{ checks: ValidationRunChecks; line: ValidationReviewCase["lineItems"][number]; productMapping: string; productMappings: Record<string, string>; quantityCorrection: string; quantityCorrections: Record<string, string>; reviewCase: ValidationReviewCase; runAction: RunReviewAction; setProductMappings: (value: Record<string, string>) => void; setQuantityCorrections: (value: Record<string, string>) => void; setUomCorrections: (value: Record<string, string>) => void; uomCorrection: string; uomCorrections: Record<string, string> }>) {
  const product = checks.productMatches.find((item) => item.extractedLineItemId === line.id);
  const uom = checks.uomNormalizations.find((item) => item.extractedLineItemId === line.id);
  const stock = checks.inventoryChecks.find((item) => item.extractedLineItemId === line.id);
  const price = checks.priceChecks.find((item) => item.extractedLineItemId === line.id);
  const discount = checks.discountChecks.find((item) => item.extractedLineItemId === line.id);
  const margin = checks.marginChecks.find((item) => item.extractedLineItemId === line.id);
  const lowConfidence = Number(line.confidence ?? 1) < 0.5;
  return (
    <tr>
      <td>{line.lineNumber}</td>
      <td>{line.rawSku || line.rawDescription || "Unspecified"}<br /><span className="muted-copy">{line.rawQuantity} {line.rawUom}</span></td>
      <td className={product && ["AMBIGUOUS", "NOT_FOUND"].includes(product.status) ? "risk-cell" : ""}>{product?.status ?? "Not checked"}<br /><span className="muted-copy">{product?.matchType ?? ""} {product?.matchedProductId ?? ""}</span><ProductCandidatePicker candidates={(reviewCase.productCandidates ?? []).filter((candidate) => candidate.extractedLineItemId === line.id)} fallbackValue={productMapping} onFallbackChange={(value) => setProductMappings({ ...productMappings, [line.id]: value })} onSelect={(productId) => runAction("Map product", () => mapReviewProduct(reviewCase.reviewCase.id, line.id, productId))} /></td>
      <td className={uom?.status === "UNKNOWN" ? "risk-cell" : ""}>{uom?.normalizedUom ?? line.normalizedUom ?? "Unknown"}<br /><span className="muted-copy">{uom?.status ?? line.validationStatus}</span><CorrectionControl label="Correct UOM" value={uomCorrection} placeholder="EA" onChange={(value) => setUomCorrections({ ...uomCorrections, [line.id]: value })} onSubmit={() => runAction("Correct UOM", () => correctReviewUom(reviewCase.reviewCase.id, line.id, uomCorrection))} /></td>
      <td className={stock && stock.status !== "AVAILABLE" ? "risk-cell" : ""}>{stock?.status ?? "Not checked"}<br /><span className="muted-copy">available {stock?.quantityAvailable ?? "n/a"}</span></td>
      <td>{price?.status ?? "Not checked"}<br /><span className="muted-copy">{price?.unitPrice ?? "n/a"} {price?.currency ?? ""}</span></td>
      <td className={discount?.requiresApproval ? "risk-cell" : ""}>{discount?.status ?? "Not checked"}<br /><span className="muted-copy">{discount?.requestedDiscountPercent ?? "n/a"}</span></td>
      <td className={margin?.requiresApproval ? "risk-cell" : ""}>{margin?.status ?? "Not checked"}<br /><span className="muted-copy">{margin?.grossMarginPercent ?? "n/a"}</span></td>
      <td className={lowConfidence ? "risk-cell" : ""}>{line.confidence ?? "n/a"}<CorrectionControl label="Correct qty" value={quantityCorrection} placeholder="2" onChange={(value) => setQuantityCorrections({ ...quantityCorrections, [line.id]: value })} onSubmit={() => runAction("Correct quantity", () => correctReviewQuantity(reviewCase.reviewCase.id, line.id, quantityCorrection))} /></td>
    </tr>
  );
}

function ApprovalRequirementsPanel({ approvalReasons, reviewCase, runAction, setApprovalReasons }: Readonly<{ approvalReasons: Record<string, string>; reviewCase: ValidationReviewCase; runAction: RunReviewAction; setApprovalReasons: (value: Record<string, string>) => void }>) {
  return (
    <section className="panel">
      <h2>Manager Approval Requirements</h2>
      {reviewCase.approvalRequirements.length === 0 ? <p>No approval requirements.</p> : (
        <ul className="review-list">
          {reviewCase.approvalRequirements.map((item) => <ApprovalRequirementItem approvalReasons={approvalReasons} item={item} key={item.id} reviewCase={reviewCase} runAction={runAction} setApprovalReasons={setApprovalReasons} />)}
        </ul>
      )}
    </section>
  );
}

function ApprovalRequirementItem({ approvalReasons, item, reviewCase, runAction, setApprovalReasons }: Readonly<{ approvalReasons: Record<string, string>; item: ValidationReviewCase["approvalRequirements"][number]; reviewCase: ValidationReviewCase; runAction: RunReviewAction; setApprovalReasons: (value: Record<string, string>) => void }>) {
  const reason = approvalReasons[item.id] ?? "";
  const risky = ["DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL", "SUBSTITUTE_REQUIRES_APPROVAL", "NEEDS_HUMAN_REVIEW"].includes(item.requirementType);
  return (
    <li className={item.status === "OPEN" || item.status === "REJECTED" || risky ? "risk-cell" : ""}>
      {item.requirementType} - {approvalLabel(item.status)} - {item.severity} - {item.reason}
      {item.status === "OPEN" ? (
        <div className="inline-correction">
          <input className="form-input compact-input" aria-label="Approval decision reason" value={reason} placeholder={risky ? "Required decision reason" : "Decision note"} onChange={(event) => setApprovalReasons({ ...approvalReasons, [item.id]: event.target.value })} />
          <button className="button secondary-button" type="button" onClick={() => runAction("Approve manager approval", () => approveReviewApproval(reviewCase.reviewCase.id, item.id, reason))}>Approve</button>
          <button className="button secondary-button" type="button" onClick={() => runAction("Reject manager approval", () => rejectReviewApproval(reviewCase.reviewCase.id, item.id, reason))}>Reject</button>
        </div>
      ) : null}
    </li>
  );
}

function SubstituteCandidatesPanel({ reviewCase, runAction }: Readonly<{ reviewCase: ValidationReviewCase; runAction: RunReviewAction }>) {
  return (
    <section className="panel">
      <h2>Substitute Candidates</h2>
      {reviewCase.substituteCandidates.length === 0 ? <p>No substitute candidates.</p> : (
        <ul className="review-list">
          {reviewCase.substituteCandidates.map((item) => (
            <li className={item.status.includes("BLOCKED") || item.requiresApproval ? "risk-cell" : ""} key={item.id}>
              {item.substituteSku ?? item.substituteProductId} - {item.status} - {item.riskLevel}{item.requiresApproval ? " - approval required" : ""}<br />
              <span className="muted-copy">stock {item.inventoryStatus ?? "n/a"} | margin {item.marginStatus ?? "n/a"} | {item.reason ?? ""}</span>
              <div className="button-row compact-actions">
                <button className="button secondary-button" disabled={item.status.includes("BLOCKED")} type="button" onClick={() => runAction("Select substitute", () => selectReviewSubstitute(reviewCase.reviewCase.id, item.id, "operator selected candidate"))}>Select</button>
                <button className="button secondary-button" type="button" onClick={() => runAction("Reject substitute", () => rejectReviewSubstitute(reviewCase.reviewCase.id, item.id, "operator rejected candidate"))}>Reject</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ValidationIssuesPanel({ issues, reviewCase, runAction, setOverrideIssueId }: Readonly<{ issues: ValidationReviewCase["issueGroups"][number]["issues"]; reviewCase: ValidationReviewCase; runAction: RunReviewAction; setOverrideIssueId: (value: string) => void }>) {
  return (
    <section className="panel">
      <h2>Validation Issues</h2>
      {issues.length === 0 ? <p>No validation issues.</p> : (
        <ul className="review-list">
          {issues.map((item) => (
            <li className={item.status === "OPEN" || item.severity !== "INFO" ? "risk-cell" : ""} key={item.id}>
              {item.issueType} - {issueLabel(reviewCase, item.id, item.status)} - {item.severity} - {item.message}
              <div className="button-row compact-actions">
                <button className="button secondary-button" disabled={item.status !== "OPEN" || isBlockingIssue(item)} type="button" onClick={() => runAction("Acknowledge issue", () => acknowledgeReviewIssue(reviewCase.reviewCase.id, item.id))}>Acknowledge</button>
                <button className="button secondary-button" disabled={item.status !== "OPEN"} type="button" onClick={() => setOverrideIssueId(item.id)}>Override</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function OverrideReasonPanel({ overrideIssueId, overrideReason, reviewCaseId, runAction, setOverrideIssueId, setOverrideReason }: Readonly<{ overrideIssueId: string; overrideReason: string; reviewCaseId: string; runAction: RunReviewAction; setOverrideIssueId: (value: string) => void; setOverrideReason: (value: string) => void }>) {
  if (!overrideIssueId) {
    return null;
  }
  return (
    <section className="panel">
      <h2>Override Reason</h2>
      <p className="risk-note">Override is audited and does not bypass backend draft preparation gates.</p>
      <textarea className="form-input" value={overrideReason} onChange={(event) => setOverrideReason(event.target.value)} placeholder="Required reason for risky override" />
      <div className="button-row">
        <button className="button" type="button" onClick={() => runAction("Override issue", async () => {
          const result = await overrideReviewIssue(reviewCaseId, overrideIssueId, overrideReason);
          if (!result.error) {
            setOverrideIssueId("");
            setOverrideReason("");
          }
          return result;
        })}>Apply Override</button>
        <button className="button secondary-button" type="button" onClick={() => setOverrideIssueId("")}>Cancel</button>
      </div>
    </section>
  );
}

function TimelinePanels({ reviewCase }: Readonly<{ reviewCase: ValidationReviewCase }>) {
  return (
    <>
      <section className="panel">
        <h2>Audit Timeline</h2>
        <Timeline items={reviewCase.timeline.map((item) => ({ action: item.actionType, message: item.message, createdAt: formatDate(item.createdAt) }))} />
      </section>
      <section className="panel">
        <h2>Correction History</h2>
        <Timeline items={(reviewCase.correctionHistory ?? []).map((item) => ({ action: item.actionType, message: item.message, createdAt: formatDate(item.createdAt) }))} />
      </section>
    </>
  );
}

function ProductCandidatePicker({ candidates, fallbackValue, onFallbackChange, onSelect }: Readonly<{ candidates: Array<{ productId: string; sku: string; name: string; matchType: string; confidence?: string | number; status: string }>; fallbackValue: string; onFallbackChange: (value: string) => void; onSelect: (productId: string) => void }>) {
  if (candidates.length > 0) {
    return (
      <div className="inline-correction">
        <select className="form-input compact-input" aria-label="Product candidate" defaultValue="" onChange={(event) => event.target.value ? onSelect(event.target.value) : undefined}>
          <option value="">Select candidate</option>
          {candidates.map((candidate) => <option key={candidate.productId} value={candidate.productId}>{candidate.sku} - {candidate.name} - {candidate.matchType} - {candidate.status}</option>)}
        </select>
      </div>
    );
  }
  return <CorrectionControl label="Map product" value={fallbackValue} placeholder="Product UUID" onChange={onFallbackChange} onSubmit={() => onSelect(fallbackValue)} />;
}

function CorrectionControl({ label, value, placeholder, onChange, onSubmit }: Readonly<{ label: string; value: string; placeholder: string; onChange: (value: string) => void; onSubmit: () => void }>) {
  return (
    <div className="inline-correction">
      <input className="form-input compact-input" aria-label={label} value={value} placeholder={placeholder} onChange={(event) => onChange(event.target.value)} />
      <button className="button secondary-button" type="button" onClick={onSubmit}>{label}</button>
    </div>
  );
}

function ReviewList({ empty, items, warningMatcher }: Readonly<{ empty: string; items: string[]; warningMatcher?: (item: string) => boolean }>) {
  if (items.length === 0) {
    return <p>{empty}</p>;
  }
  return (
    <ul className="review-list">
      {items.map((item) => <li className={warningMatcher?.(item) ? "risk-cell" : ""} key={item}>{item}</li>)}
    </ul>
  );
}

function issueLifecycleLabel(status: string) {
  return status === "OPEN" ? "unresolved" : status === "CORRECTED" ? "corrected" : status === "ACKNOWLEDGED" ? "acknowledged" : status === "OVERRIDDEN" ? "overridden" : status.toLowerCase();
}

function approvalLabel(status: string) {
  return status === "OPEN" ? "pending approval" : status === "APPROVED" ? "approved" : status === "REJECTED" ? "rejected" : status.toLowerCase();
}

function issueLabel(reviewCase: ValidationReviewCase, issueId: string, fallbackStatus: string) {
  return reviewCase.issueStatuses?.find((issue) => issue.issueId === issueId)?.lifecycleLabel ?? issueLifecycleLabel(fallbackStatus);
}

function isBlockingIssue(issue: { issueType: string; severity: string }) {
  return ["INVALID_UOM", "PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND", "INVALID_QUANTITY"].includes(issue.issueType) || issue.severity === "CRITICAL";
}

function blockerTitle(reviewCase: ValidationReviewCase, fallback: string[]) {
  const backendReasons = (reviewCase.readiness?.blockingReasons ?? reviewCase.blockingReasons ?? []).map((reason) => `${reason.issueCode}: ${reason.reason}`);
  return [...backendReasons, ...fallback].join("; ");
}

function visibleRisks(reviewCase: ValidationReviewCase, checks: ValidationRunChecks) {
  const issues = reviewCase.issueGroups.flatMap((group) => group.issues);
  const hardBlockers = [
    ...issues.filter((issue) => ["INVALID_UOM", "PRODUCT_AMBIGUOUS", "PRODUCT_NOT_FOUND"].includes(issue.issueType) && issue.status === "OPEN").map((issue) => issue.issueType),
    ...reviewCase.substituteCandidates.filter((candidate) => candidate.status.includes("BLOCKED")).map(() => "BLOCKED_SUBSTITUTE"),
    ...checks.productMatches.filter((match) => ["AMBIGUOUS", "NOT_FOUND"].includes(match.status)).map(() => "UNRESOLVED_PRODUCT_MATCH")
  ];
  const warnings = [
    ...issues.filter((issue) => ["LOW_EXTRACTION_CONFIDENCE", "MARGIN_BELOW_GUARDRAIL"].includes(issue.issueType)).map((issue) => issue.issueType),
    ...reviewCase.approvalRequirements.filter((approval) => approval.status === "OPEN").map((approval) => approval.requirementType),
    ...checks.discountChecks.filter((discount) => discount.requiresApproval).map(() => "DISCOUNT_REQUIRES_APPROVAL"),
    ...checks.marginChecks.filter((margin) => margin.requiresApproval).map(() => "MARGIN_BELOW_GUARDRAIL")
  ];
  return { hardBlockers: unique(hardBlockers), all: unique([...hardBlockers, ...warnings]) };
}

function unique(values: string[]) {
  return Array.from(new Set(values));
}

function formatDate(value?: string) {
  if (!value) return "n/a";
  return new Date(value).toLocaleString();
}
