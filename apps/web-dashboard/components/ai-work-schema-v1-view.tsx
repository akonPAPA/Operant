import {
  riskClass,
  type AiWorkDisplayField,
  type AiWorkEvidenceItem,
  type AiWorkNextActionCandidate,
  type AiWorkSuggestion
} from "@/lib/ai-work-api";

export function AiWorkSchemaV1View({
  suggestion
}: Readonly<{ suggestion: AiWorkSuggestion }>) {
  const safety = suggestion.safety;

  return (
    <div className="control-grid" data-ai-work-schema={suggestion.schemaVersion}>
      <div className="tag-row">
        <span className="status-pill">ADVISORY ONLY</span>
        <span className={`status-pill ${riskClass(suggestion.riskLevel)}`}>
          Risk: {suggestion.riskLevel}
        </span>
        {safety.humanApprovalRequired ? (
          <span className="status-pill warning">Human approval required</span>
        ) : null}
      </div>

      <p className="generated-text">{suggestion.summary}</p>
      <SchemaBody suggestion={suggestion} />

      {(suggestion.riskFlags ?? []).length > 0 ? (
        <div>
          <h4>Risk flags</h4>
          <ul className="record-list">
            {suggestion.riskFlags.map((flag) => (
              <li key={flag.code} className="record-item">
                <span className="record-title">{flag.message}</span>
                <span className={`status-pill ${riskClass(flag.severity)}`}>
                  {flag.severity}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <Evidence items={suggestion.evidence ?? []} />

      <dl className="detail-list">
        <div><dt>External execution</dt><dd>{safety.externalExecution}</dd></div>
        <div><dt>Connector call</dt><dd>{safety.connectorCall}</dd></div>
        <div><dt>Outbox</dt><dd>{safety.outbox}</dd></div>
      </dl>
    </div>
  );
}

function SchemaBody({ suggestion }: Readonly<{ suggestion: AiWorkSuggestion }>) {
  switch (suggestion.schemaVersion) {
    case "AI_WORK_SCHEMA_V1_REQUEST_SUMMARY":
      return <DisplayFields title="Request summary details" fields={suggestion.displayFields ?? []} />;
    case "AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION":
      return (
        <>
          <DisplayFields title="Suggestion details" fields={suggestion.displayFields ?? []} />
          <NextActions actions={suggestion.nextActionCandidates ?? []} />
        </>
      );
    case "AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT":
      return (
        <>
          <p className="risk-note">Draft only — not sent.</p>
          <DisplayFields title="Draft safety checks" fields={suggestion.displayFields ?? []} />
        </>
      );
    case "AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION":
      return <DisplayFields title="Validation explanation" fields={suggestion.displayFields ?? []} />;
    default:
      return <p className="risk-note">AI suggestion could not be safely rendered.</p>;
  }
}

function DisplayFields({
  title,
  fields
}: Readonly<{ title: string; fields: AiWorkDisplayField[] }>) {
  if (fields.length === 0) return null;
  return (
    <div>
      <h4>{title}</h4>
      <dl className="detail-list">
        {fields.map((field) => (
          <div key={field.key}>
            <dt>{field.label}</dt>
            <dd>
              {field.value}
              {typeof field.confidence === "number"
                ? ` · ${Math.round(field.confidence * 100)}% confidence`
                : ""}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function NextActions({ actions }: Readonly<{ actions: AiWorkNextActionCandidate[] }>) {
  if (actions.length === 0) return null;
  return (
    <div>
      <h4>Next action candidates</h4>
      <ul className="record-list">
        {actions.map((action) => (
          <li key={action.actionType} className="record-item">
            <span className="record-title">{action.label}</span>
            {action.description ? <span className="muted">{action.description}</span> : null}
            {action.requiresHumanApproval ? (
              <span className="status-pill warning">Requires human approval</span>
            ) : null}
            {action.disabledReason ? <span className="muted">{action.disabledReason}</span> : null}
          </li>
        ))}
      </ul>
      <p className="risk-note">
        Candidates are advisory. Any business action must use its own validated backend workflow.
      </p>
    </div>
  );
}

function Evidence({ items }: Readonly<{ items: AiWorkEvidenceItem[] }>) {
  if (items.length === 0) return null;
  return (
    <div>
      <h4>Evidence</h4>
      <ul className="record-list">
        {items.map((item, index) => (
          <li key={`${item.sourceType}-${index}`} className="record-item">
            <span className="record-title">{item.sourceLabel}</span>
            {item.excerpt ? <span>{item.excerpt}</span> : null}
            {typeof item.confidence === "number"
              ? <span className="muted">{Math.round(item.confidence * 100)}% confidence</span>
              : null}
          </li>
        ))}
      </ul>
    </div>
  );
}
