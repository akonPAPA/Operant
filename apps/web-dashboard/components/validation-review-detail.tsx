// OP-CAP-14B Operator Validation Review workspace (read-only).
// Presentational components over the OP-CAP-14A bounded review contract. Display only:
// no correction, approval, draft/quote/order, ERP/1C, connector or bot action is performed here.
// No raw AI advisory payload, prompt text, full document body, secret or stack trace is rendered.

import type {
  AllowedReviewAction,
  AuditTimelineItem,
  ExtractedFieldReviewItem,
  ExtractedLineItemReviewItem,
  SourceEvidenceReviewItem,
  ValidationIssueReviewItem,
  ValidationReviewDetail
} from "@/lib/validation-review-detail-api";

function show(value?: number | string | null): string {
  return value === undefined || value === null || value === "" ? "—" : String(value);
}

function formatTimestamp(ts?: string | null): string {
  if (!ts) return "—";
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return ts;
  }
}

// Short evidence reference for a field/line — points at evidence without dumping a document.
function evidenceRef(sourceEvidenceId?: string | null): string {
  return sourceEvidenceId ? `evidence ${sourceEvidenceId.slice(0, 8)}…` : "—";
}

function issueMarker(issueIds: string[]): string {
  return issueIds.length > 0 ? `${issueIds.length} issue${issueIds.length === 1 ? "" : "s"}` : "—";
}

// Human-readable label for a declarative action hint token.
function actionLabel(action: string): string {
  return action
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export function ValidationReviewHeader({ detail }: Readonly<{ detail: ValidationReviewDetail }>) {
  const { extraction, validationRun } = detail;
  return (
    <section className="panel action-panel">
      <div className="button-row">
        <span className="status-pill">Validation status: {show(validationRun.overallStatus)}</span>
        <span className="status-pill">Routing: {show(validationRun.routingDecision)}</span>
        <span className="status-pill">Advisory-only: {detail.advisoryOnly ? "yes" : "no"}</span>
      </div>
      <p className="muted-copy">
        Read-only operator review of a deterministic validation run. AI extraction is advisory; this screen does not
        create or change any quote, order, customer, inventory, price, ERP/1C or connector record.
      </p>
      <dl className="detail-list">
        <div><dt>Validation run id</dt><dd>{validationRun.validationRunId}</dd></div>
        <div><dt>Run status</dt><dd>{show(validationRun.status)}</dd></div>
        <div><dt>Extraction result id</dt><dd>{extraction.extractionResultId}</dd></div>
        <div><dt>Source / channel</dt><dd>{show(extraction.sourceType)}{extraction.sourceId ? ` · ${extraction.sourceId}` : ""}</dd></div>
        {extraction.detectedIntent ? <div><dt>Detected intent</dt><dd>{extraction.detectedIntent}</dd></div> : null}
        {extraction.documentType ? <div><dt>Document type</dt><dd>{extraction.documentType}</dd></div> : null}
        {extraction.workerStatus ? <div><dt>Worker status</dt><dd>{extraction.workerStatus}</dd></div> : null}
        <div><dt>Extraction status</dt><dd>{show(extraction.validationStatus)}</dd></div>
        <div><dt>Overall confidence</dt><dd>{show(extraction.overallConfidence)}</dd></div>
        <div><dt>Blocking issues</dt><dd>{validationRun.blockingIssueCount}</dd></div>
        <div><dt>Warning / review issues</dt><dd>{validationRun.warningReviewIssueCount}</dd></div>
        <div><dt>Approval requirements</dt><dd>{validationRun.approvalRequirementCount}</dd></div>
        <div><dt>Created</dt><dd>{formatTimestamp(validationRun.createdAt)}</dd></div>
        <div><dt>Completed</dt><dd>{formatTimestamp(validationRun.completedAt)}</dd></div>
      </dl>
    </section>
  );
}

export function ExtractedFieldsPanel({ fields }: Readonly<{ fields: ExtractedFieldReviewItem[] }>) {
  return (
    <section className="panel table-panel">
      <h2>Extracted fields</h2>
      {fields.length === 0 ? (
        <p className="muted-copy">No extracted fields on this validation run.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr><th>Field</th><th>Extracted</th><th>Normalized</th><th>Confidence</th><th>Status</th><th>Evidence</th><th>Issues</th></tr>
          </thead>
          <tbody>
            {fields.map((f) => (
              <tr key={f.fieldId}>
                <td>{f.fieldName}</td>
                <td>{show(f.extractedValue)}</td>
                <td>{show(f.normalizedValue)}</td>
                <td>{show(f.confidence)}</td>
                <td><span className="status-pill">{show(f.validationStatus)}</span></td>
                <td>{evidenceRef(f.sourceEvidenceId)}</td>
                <td>{issueMarker(f.issueIds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

export function ExtractedLineItemsTable({ lineItems }: Readonly<{ lineItems: ExtractedLineItemReviewItem[] }>) {
  return (
    <section className="panel table-panel">
      <h2>Line items</h2>
      {lineItems.length === 0 ? (
        <p className="muted-copy">No extracted line items on this validation run.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr><th>#</th><th>Raw SKU</th><th>Matched product</th><th>Description</th><th>Qty</th><th>UOM</th><th>Confidence</th><th>Status</th><th>Evidence</th><th>Issues</th></tr>
          </thead>
          <tbody>
            {lineItems.map((l) => (
              <tr key={l.lineItemId}>
                <td>{l.lineNumber}</td>
                <td>{show(l.rawSku)}</td>
                <td>{l.matchedProductId ? `${l.matchedProductId.slice(0, 8)}… (${show(l.matchStatus)})` : show(l.matchStatus)}</td>
                <td>{show(l.description)}</td>
                <td>{show(l.quantity)}</td>
                <td>{show(l.uom)}</td>
                <td>{show(l.confidence)}</td>
                <td><span className="status-pill">{show(l.validationStatus)}</span></td>
                <td>{evidenceRef(l.sourceEvidenceId)}</td>
                <td>{issueMarker(l.issueIds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

export function ValidationIssuesPanel({ issues }: Readonly<{ issues: ValidationIssueReviewItem[] }>) {
  return (
    <section className="panel table-panel">
      <h2>Validation issues</h2>
      {issues.length === 0 ? (
        <p className="muted-copy">No validation issues were raised for this run.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr><th>Severity</th><th>Code</th><th>Target</th><th>Blocking</th><th>Explanation</th><th>Status</th></tr>
          </thead>
          <tbody>
            {issues.map((i) => (
              <tr key={i.issueId}>
                <td><span className="status-pill">{i.severity}</span></td>
                <td>{i.code}</td>
                <td>{i.targetType}{i.targetLineNumber != null ? ` #${i.targetLineNumber}` : i.targetId ? ` ${i.targetId.slice(0, 8)}…` : ""}</td>
                <td>{i.blocking ? "Blocking" : "No"}</td>
                <td>{i.message}</td>
                <td>{i.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

export function SourceEvidencePanel({ evidence }: Readonly<{ evidence: SourceEvidenceReviewItem[] }>) {
  return (
    <section className="panel">
      <h2>Source evidence</h2>
      <p className="muted-copy">Bounded source snippets only. Full document, message body and AI payload are intentionally not shown.</p>
      {evidence.length === 0 ? (
        <p className="muted-copy">No source evidence snippets are linked to this run.</p>
      ) : (
        <ul className="evidence-list">
          {evidence.map((e) => (
            <li key={e.sourceEvidenceId}>
              <div className="button-row">
                <span className="status-pill">{show(e.evidenceType)}</span>
                {e.pageNumber != null ? <span className="status-pill">page {e.pageNumber}</span> : null}
                {e.startOffset != null ? <span className="status-pill">offset {e.startOffset}–{show(e.endOffset)}</span> : null}
              </div>
              <p className="evidence-snippet">{show(e.snippet)}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function AuditTimelinePanel({ timeline }: Readonly<{ timeline: AuditTimelineItem[] }>) {
  return (
    <section className="panel table-panel">
      <h2>Audit timeline</h2>
      {timeline.length === 0 ? (
        <p className="muted-copy">No audit activity has been recorded for this validation run yet.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr><th>When</th><th>Action</th><th>Entity</th><th>Actor</th></tr>
          </thead>
          <tbody>
            {timeline.map((a, index) => (
              <tr key={`${a.action}-${a.occurredAt}-${index}`}>
                <td>{formatTimestamp(a.occurredAt)}</td>
                <td>{a.action}</td>
                <td>{a.entityType} {a.entityId.slice(0, 8)}…</td>
                <td>{show(a.actorId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

export function AllowedActionsPanel({ actions }: Readonly<{ actions: AllowedReviewAction[] }>) {
  return (
    <section className="panel">
      <h2>Allowed next actions</h2>
      <p className="risk-note">
        These are declarative safety hints from the backend, not live controls. Correction and approval commands arrive
        in OP-CAP-14C; nothing here writes business data.
      </p>
      {actions.length === 0 ? (
        <p className="muted-copy">No next-action hints are available.</p>
      ) : (
        <ul className="action-hint-list">
          {actions.map((a) => (
            <li key={a.action} className={a.enabled ? "action-hint enabled" : "action-hint disabled"}>
              <span className="status-pill">{a.enabled ? "available" : "not yet implemented"}</span>
              <strong>{actionLabel(a.action)}</strong>
              {a.requiredPermission ? <span className="muted-copy"> requires {a.requiredPermission}</span> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function ValidationReviewDetailView({ detail }: Readonly<{ detail: ValidationReviewDetail }>) {
  return (
    <div className="review-workspace">
      <ValidationReviewHeader detail={detail} />
      <ExtractedFieldsPanel fields={detail.fields} />
      <ExtractedLineItemsTable lineItems={detail.lineItems} />
      <ValidationIssuesPanel issues={detail.issues} />
      <SourceEvidencePanel evidence={detail.sourceEvidence} />
      <AuditTimelinePanel timeline={detail.auditTimeline} />
      <AllowedActionsPanel actions={detail.allowedActions} />
    </div>
  );
}
