"use client";

import { useState } from "react";

import { updateBotRuntimeSettings, type BotConversationDetail, type BotHandoff, type BotRuntimeSettings } from "@/lib/bot-runtime-api";

const FLOWS = [
  "GREETING",
  "CHECK_AVAILABILITY",
  "CHECK_PRICE",
  "REQUEST_QUOTE",
  "SUGGEST_SUBSTITUTE",
  "ORDER_OR_QUOTE_STATUS",
  "HUMAN_HANDOFF",
  "UNSUPPORTED_REQUEST_SAFE_REPLY"
];

type ActionState = {
  status: "idle" | "loading" | "success" | "error";
  message: string;
};

export function BotSettingsWorkspace({ initialSettings, initialHandoffs, initialDetails }: Readonly<{ initialSettings: BotRuntimeSettings; initialHandoffs: BotHandoff[]; initialDetails: BotConversationDetail[] }>) {
  const [settings, setSettings] = useState(initialSettings);
  const [action, setAction] = useState<ActionState>({ status: "idle", message: "" });

  async function save(next: BotRuntimeSettings) {
    setSettings(next);
    setAction({ status: "loading", message: "Saving bot policy settings..." });
    const result = await updateBotRuntimeSettings({
      enabled: next.enabled,
      allowedFlows: next.allowedFlows,
      defaultHandoffQueue: next.defaultHandoffQueue
    });
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Bot settings were not saved." });
      return;
    }
    setSettings(result.data);
    setAction({ status: "success", message: "Bot settings saved through the tenant-scoped backend command path." });
  }

  function toggleFlow(flow: string) {
    const allowedFlows = settings.allowedFlows.includes(flow)
      ? settings.allowedFlows.filter((item) => item !== flow)
      : [...settings.allowedFlows, flow];
    void save({ ...settings, allowedFlows });
  }

  return (
    <div className="review-workspace">
      <section className="panel action-panel">
        <h2>Telegram Runtime</h2>
        <dl className="detail-list">
          <div><dt>Connection</dt><dd>{settings.connectionId ?? "Not created yet"}</dd></div>
          <div><dt>Channel</dt><dd>{settings.channelType}</dd></div>
          <div><dt>Status</dt><dd><span className={`status-pill ${settings.enabled ? "done" : "warning"}`}>{settings.enabled ? "Enabled" : "Disabled"}</span></dd></div>
          <div><dt>Last seen</dt><dd>{formatDate(settings.lastSeenAt)}</dd></div>
          <div><dt>Default handoff queue</dt><dd>{settings.defaultHandoffQueue}</dd></div>
        </dl>
        <div className="button-row">
          <button className="button" type="button" disabled={action.status === "loading"} onClick={() => save({ ...settings, enabled: !settings.enabled })}>
            {settings.enabled ? "Disable bot" : "Enable bot"}
          </button>
          <input
            className="form-input"
            aria-label="Default handoff queue"
            value={settings.defaultHandoffQueue}
            onChange={(event) => setSettings({ ...settings, defaultHandoffQueue: event.target.value })}
            onBlur={() => save(settings)}
          />
        </div>
        {action.message ? <p className={`form-message ${action.status === "error" ? "error" : action.status === "success" ? "done" : ""}`}>{action.message}</p> : null}
      </section>

      <section className="panel table-panel">
        <h2>Allowed Flows</h2>
        <p className="risk-note">Disabled flows are denied by backend policy and routed to operator handoff. The bot cannot approve quotes, orders, discounts, or substitutes.</p>
        <div className="control-grid">
          {FLOWS.map((flow) => {
            const enabled = settings.allowedFlows.includes(flow);
            return (
              <label className="check-row" key={flow}>
                <input type="checkbox" checked={enabled} disabled={action.status === "loading"} onChange={() => toggleFlow(flow)} />
                <span>{flow}</span>
                <span className={`status-pill ${enabled ? "done" : "warning"}`}>{enabled ? "Enabled" : "Disabled"}</span>
              </label>
            );
          })}
        </div>
      </section>

      <section className="panel table-panel">
        <h2>Safe Templates</h2>
        <table className="data-table">
          <thead><tr><th>Template boundary</th><th>Policy result</th></tr></thead>
          <tbody>
            {settings.safeResponseTemplates.map((template) => (
              <tr key={template}><td>{template}</td><td>No autonomous trusted-state mutation</td></tr>
            ))}
            {settings.safeResponseTemplates.length === 0 ? <tr><td colSpan={2}>No templates are available until the backend settings endpoint is reachable.</td></tr> : null}
          </tbody>
        </table>
      </section>

      <section className="panel table-panel">
        <h2>Handoff Queue</h2>
        <p className="risk-note">Every queued item is review-only. External execution remains disabled until an operator uses an approved workflow.</p>
        <table className="data-table">
          <thead><tr><th>Reason</th><th>Status</th><th>Intent</th><th>Source context</th><th>Risk flags</th></tr></thead>
          <tbody>
            {initialHandoffs.map((handoff) => (
              <tr key={handoff.id}>
                <td>{handoff.reason}<br /><span className="muted-copy">{handoff.assignedQueue ?? "BOT_REVIEW"}</span></td>
                <td><span className="status-pill warning">{handoff.status}</span></td>
                <td>{handoff.detectedIntent ?? "n/a"}</td>
                <td>conversation {shortId(handoff.conversationId)}<br /><span className="muted-copy">channel message {shortId(handoff.channelMessageId)}</span></td>
                <td>{handoff.riskFlagsJson ?? "[]"}</td>
              </tr>
            ))}
            {initialHandoffs.length === 0 ? <tr><td colSpan={5}>No bot handoffs are queued for the current tenant.</td></tr> : null}
          </tbody>
        </table>
      </section>

      <section className="panel table-panel">
        <h2>Conversation Audit</h2>
        <table className="data-table">
          <thead><tr><th>Conversation</th><th>Latest message</th><th>Intent</th><th>Policy</th><th>Linked review</th></tr></thead>
          <tbody>
            {initialDetails.map((detail) => {
              const latest = detail.messages.at(-1);
              return (
                <tr key={detail.conversation.id}>
                  <td>{detail.conversation.channel}<br /><span className="muted-copy">{detail.conversation.externalChatId}</span></td>
                  <td>{latest?.rawText ?? "No messages"}</td>
                  <td>{latest?.detectedIntent ?? "n/a"}</td>
                  <td>{detail.conversation.policyDecision ?? "n/a"}<br /><span className="muted-copy">{detail.conversation.suggestedNextAction ?? ""}</span></td>
                  <td>{detail.conversation.linkedReviewCaseId ? <a href={`/quote-review?source=bot&caseId=${detail.conversation.linkedReviewCaseId}`}>Open linked review</a> : "No linked quote review"}</td>
                </tr>
              );
            })}
            {initialDetails.length === 0 ? <tr><td colSpan={5}>No conversations are available for audit display.</td></tr> : null}
          </tbody>
        </table>
      </section>
    </div>
  );
}

function formatDate(value?: string) {
  if (!value) return "n/a";
  return new Date(value).toLocaleString();
}

function shortId(value?: string | null) {
  if (!value) return "n/a";
  return value.length > 8 ? value.slice(0, 8) : value;
}
