import Link from "next/link";
import {
  isTerminal,
  statusLabel,
  type RfqHandoff,
  type RfqHandoffDecisionResult,
  type RfqHandoffDraftQuote
} from "@/lib/rfq-handoff-api";
import type { AiWorkSuggestion } from "@/lib/ai-work-api";

// PR #255 Operator Cockpit v1 — guided RFQ-to-quote journey (read/display only).
// This component renders a coherent, honest, step-by-step view of ONE RFQ handoff so the operator
// can see the whole demo path at a glance and know the next safe action. It performs no mutation and
// takes only the operator-safe props the workspace already holds. Where a step has not happened yet,
// it shows an explicit NOT_* state token instead of a fake counter or an implied production commitment.

type JourneyTone = "done" | "pending" | "muted";

type JourneyStep = {
  key: string;
  title: string;
  value: string;
  tone: JourneyTone;
  note?: string;
};

function toneClass(tone: JourneyTone): string {
  switch (tone) {
    case "done":
      return "done";
    case "pending":
      return "warning";
    default:
      return "";
  }
}

// The single, obvious next operator action derived purely from the safe workflow state.
function nextAction(
  detail: RfqHandoff,
  draftResult: RfqHandoffDraftQuote | null,
  decisionResult: RfqHandoffDecisionResult | null
): string {
  if (decisionResult) {
    return "Safe demo terminal reached. No further operator action — review commerce intelligence or runtime control below.";
  }
  if (draftResult) {
    return "Complete or decline the safe demo terminal for this draft. Neither approves a quote or enables external execution.";
  }
  if (isTerminal(detail.status)) {
    return "This handoff is in a terminal state. No further transition is allowed.";
  }
  if (detail.status === "PENDING_REVIEW") {
    return "Take this handoff into review, then inspect the request before creating a draft quote.";
  }
  if (detail.status === "IN_REVIEW") {
    return "Create or inspect the draft quote for this handoff, or generate an advisory suggestion first.";
  }
  return "Review this handoff.";
}

export function RfqCockpitJourney({
  detail,
  aiSuggestion,
  draftResult,
  decisionResult
}: Readonly<{
  detail: RfqHandoff;
  aiSuggestion: AiWorkSuggestion | null;
  draftResult: RfqHandoffDraftQuote | null;
  decisionResult: RfqHandoffDecisionResult | null;
}>) {
  const requestText = detail.requestText ?? detail.requestPreview ?? "";

  const steps: JourneyStep[] = [
    {
      key: "source",
      title: "Source channel",
      value: detail.sourceChannel,
      tone: "done"
    },
    {
      key: "intent",
      title: "Detected intent",
      value: detail.detectedIntent ?? "NOT_DETECTED",
      tone: detail.detectedIntent ? "done" : "muted"
    },
    {
      key: "request",
      title: "Request text",
      value: requestText ? requestText : "NOT_PROVIDED",
      tone: requestText ? "done" : "muted"
    },
    {
      key: "handoff",
      title: "Handoff status",
      value: statusLabel(detail.status),
      tone: isTerminal(detail.status) ? "done" : "pending"
    },
    {
      key: "advisory",
      title: "AI advisory status",
      value: aiSuggestion ? "GENERATED (advisory only)" : "NOT_GENERATED",
      tone: aiSuggestion ? "done" : "muted",
      note: aiSuggestion
        ? "Advisory suggestion only. It does not approve or change any business state."
        : "No advisory suggestion has been generated for this handoff yet."
    },
    {
      key: "draft",
      title: "Draft quote status",
      value: draftResult ? `CREATED · ${draftResult.draftQuote.status}` : "NOT_CREATED",
      tone: draftResult ? "done" : "muted",
      note: draftResult
        ? "Internal review-required draft only. It approves nothing and triggers no external write."
        : "No draft quote has been created for this handoff yet."
    },
    {
      key: "lines",
      title: "Draft line count",
      value: draftResult ? String(draftResult.draftQuote.lines.length) : "NOT_MEASURED",
      tone: draftResult ? "done" : "muted"
    },
    {
      key: "terminal",
      title: "Safe terminal state",
      value: decisionResult ? decisionResult.terminalState : "NOT_RECORDED",
      tone: decisionResult ? "done" : "muted",
      note: decisionResult
        ? `Decision ${decisionResult.decision} → ${decisionResult.quoteState}. This is a safe demo terminal, not quote approval.`
        : "No terminal demo decision has been recorded for this handoff yet."
    },
    {
      key: "audit",
      title: "Audit status",
      value: decisionResult?.auditStatus ?? draftResult?.auditStatus ?? "NOT_RECORDED",
      tone: decisionResult || draftResult ? "done" : "muted"
    }
  ];

  return (
    <section className="panel action-panel">
      <h2>Guided RFQ-to-quote cockpit</h2>
      <p className="muted-copy">
        A coherent view of this one handoff&apos;s demo journey. Steps that have not happened yet show an
        explicit NOT_* state — no fake counters, no implied ERP/order/customer commitment.
      </p>

      <div className="control-grid">
        <h3>Next operator action</h3>
        <p className="risk-note">{nextAction(detail, draftResult, decisionResult)}</p>
      </div>

      <ol className="demo-timeline">
        {steps.map((step, index) => (
          <li key={step.key}>
            <span>{index + 1}</span>
            <div>
              <strong>{step.title}</strong>
              <div>
                <span className={`status-pill ${toneClass(step.tone)}`}>{step.value}</span>
              </div>
              {step.note ? <p className="muted-copy">{step.note}</p> : null}
            </div>
          </li>
        ))}
      </ol>

      <div className="control-grid">
        <h3>Safety posture</h3>
        <dl className="detail-list">
          <div>
            <dt>External execution</dt>
            <dd>
              <span className="status-pill ok">DISABLED</span>
            </dd>
          </div>
          <div>
            <dt>Connector call</dt>
            <dd>{decisionResult?.connectorAction ?? "NOT_INVOKED"}</dd>
          </div>
          <div>
            <dt>Outbox</dt>
            <dd>{decisionResult?.outboxStatus ?? draftResult?.outboxStatus ?? "NOT_REQUESTED"}</dd>
          </div>
          <div>
            <dt>External write safety</dt>
            <dd>{draftResult?.externalWriteSafety ?? "NO_EXTERNAL_WRITE"}</dd>
          </div>
        </dl>
        <p className="risk-note">
          No ERP/1C/accounting write, connector command, outbox execution, or autonomous AI approval is
          possible from this cockpit. AI suggests, rules validate, the operator decides, the backend writes,
          and every transition is tenant-scoped and audited.
        </p>
      </div>

      <div className="control-grid">
        <h3>Related operator views</h3>
        <div className="button-row">
          <Link className="button secondary-button" href="/commerce-intelligence">
            Commerce Intelligence
          </Link>
          <Link className="button secondary-button" href="/runtime-control">
            Runtime Control
          </Link>
        </div>
        <p className="muted-copy">
          These read-only views show the same tenant-scoped demo data from a different angle. They do not
          approve, execute, or mutate this handoff.
        </p>
      </div>
    </section>
  );
}
