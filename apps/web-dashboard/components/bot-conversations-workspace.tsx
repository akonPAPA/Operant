"use client";

import { useState } from "react";

import { createBotResponseDraft, createBotReviewHandoff, markBotResponseReady, simulateBotMessage, stubSendBotResponse, type BotConversationDetail, type BotResponseDraft, type BotReviewHandoff, type BotSimulateMessageResponse } from "@/lib/bot-runtime-api";

type ActionState = {
  status: "idle" | "loading" | "success" | "error";
  message: string;
};

export function BotConversationsWorkspace({ initialDetails }: Readonly<{ initialDetails: BotConversationDetail[] }>) {
  const [details, setDetails] = useState(initialDetails);
  const [text, setText] = useState("Need quote for 2 EA PAD-OE-04465");
  const [simulation, setSimulation] = useState<BotSimulateMessageResponse | null>(null);
  const [reviewHandoffs, setReviewHandoffs] = useState<Record<string, BotReviewHandoff>>({});
  const [action, setAction] = useState<ActionState>({ status: "idle", message: "" });

  async function runSimulation() {
    setAction({ status: "loading", message: "Simulating bot intake..." });
    const result = await simulateBotMessage(text);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Simulation failed." });
      return;
    }
    setSimulation(result.data);
    setAction({ status: "success", message: "Simulation captured by backend bot runtime." });
    setDetails((current) => current);
  }

  async function draftResponse(conversationId: string, sourceMessageId?: string) {
    setAction({ status: "loading", message: "Creating operator-reviewed response draft..." });
    const result = await createBotResponseDraft(conversationId, sourceMessageId);
    handleDraftResult(result.data, result.error);
  }

  async function markReady(responseId: string) {
    setAction({ status: "loading", message: "Marking response draft ready for local stub send..." });
    const result = await markBotResponseReady(responseId);
    handleDraftResult(result.data, result.error);
  }

  async function stubSend(responseId: string) {
    setAction({ status: "loading", message: "Recording local stub send. No Telegram network call is made." });
    const result = await stubSendBotResponse(responseId);
    handleDraftResult(result.data, result.error);
  }

  function handleDraftResult(draft?: BotResponseDraft, error?: string) {
    if (error || !draft) {
      setAction({ status: "error", message: error ?? "Response draft action failed." });
      return;
    }
    setAction({ status: "success", message: `Response draft ${draft.status}. This is not a real Telegram send.` });
    setDetails((current) => current.map((item) => {
      if (item.conversation.id !== draft.conversationId) return item;
      const existing = item.responseDrafts ?? [];
      const nextDrafts = existing.some((candidate) => candidate.id === draft.id)
        ? existing.map((candidate) => candidate.id === draft.id ? draft : candidate)
        : [draft, ...existing];
      return { ...item, responseDrafts: nextDrafts };
    }));
  }

  async function createReviewHandoff(conversationId: string) {
    setAction({ status: "loading", message: "Creating operator review handoff..." });
    const result = await createBotReviewHandoff(conversationId);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Review handoff failed." });
      return;
    }
    handleReviewHandoff(result.data);
  }

  function handleReviewHandoff(handoff: BotReviewHandoff) {
    setAction({ status: "success", message: handoff.reusedExisting ? "Existing operator review handoff reused." : "Operator review handoff created." });
    setReviewHandoffs((current) => ({ ...current, [handoff.conversationId]: handoff }));
    setDetails((current) => current.map((item) => item.conversation.id === handoff.conversationId
      ? { ...item, conversation: { ...item.conversation, linkedReviewCaseId: handoff.reviewCaseId, status: "LINKED_TO_REVIEW", requiresHumanReview: true } }
      : item));
  }

  return (
    <div className="review-workspace">
      <SimulationPanel action={action} onRun={runSimulation} simulation={simulation} text={text} onTextChange={setText} />
      <ConversationListPanel details={details} reviewHandoffs={reviewHandoffs} loading={action.status === "loading"} onCreateReviewHandoff={createReviewHandoff} />
      <OperatorHandoffsPanel details={details} reviewHandoffs={reviewHandoffs} />
      <ResponseDraftsPanel details={details} loading={action.status === "loading"} onDraftResponse={draftResponse} onMarkReady={markReady} onStubSend={stubSend} />
      <RuntimeBoundaryPanel />
    </div>
  );
}

function SimulationPanel({ action, onRun, simulation, text, onTextChange }: Readonly<{ action: ActionState; onRun: () => void; simulation: BotSimulateMessageResponse | null; text: string; onTextChange: (value: string) => void }>) {
  return (
    <section className="panel action-panel">
      <h2>Local Simulation</h2>
      <p className="risk-note">Simulation uses the tenant-scoped Core API bot runtime. It captures messages and review handoffs only.</p>
      <textarea className="form-input" value={text} onChange={(event) => onTextChange(event.target.value)} aria-label="Bot simulation message" />
      <div className="button-row">
        <button className="button" type="button" disabled={action.status === "loading"} onClick={onRun}>Simulate Telegram Message</button>
      </div>
      {action.message ? <p className={`form-message ${action.status === "error" ? "error" : action.status === "success" ? "done" : ""}`}>{action.message}</p> : null}
      {simulation ? <SimulationResult simulation={simulation} /> : null}
    </section>
  );
}

function SimulationResult({ simulation }: Readonly<{ simulation: BotSimulateMessageResponse }>) {
  return (
    <dl className="detail-list">
      <div><dt>Intent</dt><dd>{simulation.intent}</dd></div>
      <div><dt>Policy</dt><dd>{simulation.policyDecision}</dd></div>
      <div><dt>Handoff</dt><dd>{simulation.requiresHumanReview ? "Required" : "Not required"}</dd></div>
      <div><dt>RFQ request</dt><dd>{simulation.createdRfqDraftId ?? "None"}</dd></div>
      <div><dt>Safe response</dt><dd>{simulation.suggestedSafeResponse}</dd></div>
    </dl>
  );
}

function ConversationListPanel({ details, reviewHandoffs, loading, onCreateReviewHandoff }: Readonly<{ details: BotConversationDetail[]; reviewHandoffs: Record<string, BotReviewHandoff>; loading: boolean; onCreateReviewHandoff: (conversationId: string) => void }>) {
  return (
    <section className="panel table-panel">
      <h2>Conversation List</h2>
      <table className="data-table">
        <thead>
          <tr><th>Conversation</th><th>Latest message</th><th>Intent</th><th>Policy decision</th><th>Review handoff</th><th>Updated</th></tr>
        </thead>
        <tbody>
          {details.map((item) => <ConversationRow item={item} key={item.conversation.id} loading={loading} reviewHandoff={reviewHandoffs[item.conversation.id]} onCreateReviewHandoff={onCreateReviewHandoff} />)}
          {details.length === 0 ? <tr><td colSpan={6}>No bot conversations are available for the current tenant.</td></tr> : null}
        </tbody>
      </table>
    </section>
  );
}

function ConversationRow({ item, loading, reviewHandoff, onCreateReviewHandoff }: Readonly<{ item: BotConversationDetail; loading: boolean; reviewHandoff?: BotReviewHandoff; onCreateReviewHandoff: (conversationId: string) => void }>) {
  const latest = item.messages.at(-1);
  const handoffOpen = item.handoffs.some((handoff) => handoff.status === "OPEN");
  return (
    <tr>
      <td>{item.conversation.channel}<br /><span className="muted-copy">{item.conversation.externalChatId}</span></td>
      <td>{latest?.rawText ?? "No messages"}<br /><span className="muted-copy">{latest?.externalMessageId ?? item.conversation.id}</span></td>
      <td>{latest?.detectedIntent ?? "n/a"}</td>
      <td><span className={`status-pill ${item.conversation.requiresHumanReview ? "warning" : ""}`}>{item.conversation.policyDecision ?? "n/a"}</span><br /><span className="muted-copy">{item.conversation.suggestedNextAction ?? ""}</span></td>
      <td>
        <ReviewHandoffCell item={item} handoffOpen={handoffOpen} loading={loading} reviewHandoff={reviewHandoff} onCreateReviewHandoff={onCreateReviewHandoff} />
      </td>
      <td>{formatDate(item.conversation.updatedAt)}</td>
    </tr>
  );
}

function ReviewHandoffCell({ item, handoffOpen, loading, reviewHandoff, onCreateReviewHandoff }: Readonly<{ item: BotConversationDetail; handoffOpen: boolean; loading: boolean; reviewHandoff?: BotReviewHandoff; onCreateReviewHandoff: (conversationId: string) => void }>) {
  return (
    <>
      {item.conversation.linkedReviewCaseId ? (
        <>
          <span className="status-pill warning">Operator handoff case</span>
          <br /><span className="muted-copy">Bot-only handoff is not validation-review ready.</span>
        </>
      ) : handoffOpen || item.conversation.requiresHumanReview ? (
        <button className="button secondary" type="button" disabled={loading} onClick={() => onCreateReviewHandoff(item.conversation.id)}>Create operator review handoff</button>
      ) : "Not required"}
      <br /><span className="muted-copy">{item.handoffs[0]?.reason ?? "No handoff reason"}</span>
      {reviewHandoff ? <><br /><span className="muted-copy">{reviewHandoff.nextActions.join(" | ")}</span></> : null}
    </>
  );
}

function OperatorHandoffsPanel({ details, reviewHandoffs }: Readonly<{ details: BotConversationDetail[]; reviewHandoffs: Record<string, BotReviewHandoff> }>) {
  const rows = details.filter((item) => item.conversation.linkedReviewCaseId || reviewHandoffs[item.conversation.id]);
  return (
    <section className="panel table-panel">
      <h2>Operator Handoffs</h2>
      <p className="risk-note">Bot-originated handoffs are operator queue cases, not validation-backed readiness cases. Draft quote and draft order preparation are unavailable here.</p>
      <table className="data-table">
        <thead>
          <tr><th>Conversation</th><th>Case</th><th>Source</th><th>Latest message</th><th>Next actions</th></tr>
        </thead>
        <tbody>
          {rows.map((item) => <OperatorHandoffRow handoff={reviewHandoffs[item.conversation.id]} item={item} key={`${item.conversation.id}-handoff`} />)}
          {rows.length === 0 ? <tr><td colSpan={5}>No linked operator handoff cases are available.</td></tr> : null}
        </tbody>
      </table>
    </section>
  );
}

function OperatorHandoffRow({ handoff, item }: Readonly<{ handoff?: BotReviewHandoff; item: BotConversationDetail }>) {
  const latest = item.messages.at(-1);
  return (
    <tr>
      <td>{item.conversation.channel}<br /><span className="muted-copy">{item.conversation.externalChatId}</span></td>
      <td>Operator handoff case<br /><span className="muted-copy">{handoff?.reviewCaseId ?? item.conversation.linkedReviewCaseId}</span></td>
      <td>{handoff?.sourceType ?? "BOT_CONVERSATION"}<br /><span className="muted-copy">{handoff?.sourceConversationId ?? item.conversation.id}</span><br /><span className="muted-copy">{handoff?.policyDecision ?? item.conversation.policyDecision ?? "policy pending"}</span></td>
      <td>{handoff?.latestMessage ?? latest?.rawText ?? "No messages"}<br /><span className="muted-copy">{handoff?.detectedIntent ?? latest?.detectedIntent ?? "n/a"}</span></td>
      <td>{handoff?.nextActions.join(" | ") ?? "Open the bot conversation queue"}</td>
    </tr>
  );
}

function ResponseDraftsPanel({ details, loading, onDraftResponse, onMarkReady, onStubSend }: Readonly<{ details: BotConversationDetail[]; loading: boolean; onDraftResponse: (conversationId: string, sourceMessageId?: string) => void; onMarkReady: (responseId: string) => void; onStubSend: (responseId: string) => void }>) {
  return (
    <section className="panel table-panel">
      <h2>Response Drafts</h2>
      <p className="risk-note">Phase 7C drafts bounded operator-assisted replies only. Stub send records a local no-op state and does not contact Telegram.</p>
      <table className="data-table">
        <thead>
          <tr><th>Conversation</th><th>Draft text</th><th>Policy</th><th>Review</th><th>Status</th><th>Local action</th></tr>
        </thead>
        <tbody>
          {details.map((item) => <ResponseDraftRow item={item} key={`${item.conversation.id}-responses`} loading={loading} onDraftResponse={onDraftResponse} onMarkReady={onMarkReady} onStubSend={onStubSend} />)}
          {details.length === 0 ? <tr><td colSpan={6}>No bot conversations are available for response drafting.</td></tr> : null}
        </tbody>
      </table>
    </section>
  );
}

function ResponseDraftRow({ item, loading, onDraftResponse, onMarkReady, onStubSend }: Readonly<{ item: BotConversationDetail; loading: boolean; onDraftResponse: (conversationId: string, sourceMessageId?: string) => void; onMarkReady: (responseId: string) => void; onStubSend: (responseId: string) => void }>) {
  const latest = item.messages.at(-1);
  const drafts = item.responseDrafts ?? [];
  return (
    <tr>
      <td>{item.conversation.channel}<br /><span className="muted-copy">{item.conversation.externalChatId}</span></td>
      <td>{drafts[0]?.responseText ?? "No response draft yet."}</td>
      <td>{drafts[0]?.policyDecision ?? item.conversation.policyDecision ?? "n/a"}</td>
      <td>{drafts[0]?.requiresOperatorReview ? "Operator review required" : "Ready"}</td>
      <td>{drafts[0]?.status ?? "Not drafted"}</td>
      <td>
        <div className="button-row">
          <button className="button secondary" type="button" disabled={!latest || loading} onClick={() => onDraftResponse(item.conversation.id, latest?.id)}>Draft response</button>
          <button className="button secondary" type="button" disabled={!drafts[0] || drafts[0].status === "READY_FOR_STUB_SEND" || drafts[0].status === "STUB_SENT" || loading} onClick={() => onMarkReady(drafts[0].id)}>Mark ready</button>
          <button className="button secondary" type="button" disabled={!drafts[0] || drafts[0].status !== "READY_FOR_STUB_SEND" || loading} onClick={() => onStubSend(drafts[0].id)}>Local stub send</button>
        </div>
      </td>
    </tr>
  );
}

function RuntimeBoundaryPanel() {
  return (
    <section className="panel">
      <h2>Runtime Boundary</h2>
      <p className="risk-note">The bot runtime cannot approve quotes, finalize orders, approve discounts, approve substitutes, reserve inventory, execute connectors, send real Telegram messages, or write to ERP.</p>
    </section>
  );
}

function formatDate(value?: string) {
  if (!value) return "n/a";
  return new Date(value).toLocaleString();
}
