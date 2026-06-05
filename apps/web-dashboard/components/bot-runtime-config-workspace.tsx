"use client";

import { useMemo, useState } from "react";

import {
  getBotRuntimeConfiguration,
  resetBotRuntimeConfiguration,
  updateBotRuntimeConfiguration,
  type BotRuntimeConfig,
  type BotRuntimeConfigListItem,
  type BotRuntimeConfigUpdate
} from "@/lib/bot-runtime-config-api";

// Exact backend enum values — do not invent or rename. Sourced from the OP-CAP-06B domain enums.
const FLOW_MODE_OPTIONS = ["DISABLED", "OPERATOR_REVIEW_ONLY", "CONTROLLED_DRAFT", "CONTROLLED_RESPONSE"] as const;
const PRICE_VISIBILITY_OPTIONS = ["NEVER", "IDENTIFIED_CUSTOMER_ONLY", "AUTHORIZED_CUSTOMER_ONLY"] as const;
const UNKNOWN_CUSTOMER_OPTIONS = ["HANDOFF", "SAFE_GENERIC_REPLY", "REJECT"] as const;
const INVENTORY_FRESHNESS_OPTIONS = ["STRICT", "WARN_AND_HANDOFF", "ALLOW_WITH_WARNING"] as const;

const FRESHNESS_MIN = 1;
const FRESHNESS_MAX = 10080;

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };

type ModeFlow = {
  key: "priceCheckMode" | "rfqCaptureMode" | "substituteSuggestionMode" | "orderStatusMode";
  label: string;
  description: string;
  risk: string;
};

const MODE_FLOWS: ModeFlow[] = [
  {
    key: "priceCheckMode",
    label: "Price check",
    description: "How the bot handles price questions.",
    risk: "Price answers are only safe for identified/authorized customers and backend policy still applies."
  },
  {
    key: "rfqCaptureMode",
    label: "RFQ capture",
    description: "How the bot handles quote/RFQ requests.",
    risk: "RFQ creates draft/review work only. It does not approve final orders."
  },
  {
    key: "substituteSuggestionMode",
    label: "Substitute suggestion",
    description: "How the bot handles substitute/alternative requests.",
    risk: "Substitute suggestions remain subject to deterministic compatibility/risk validation."
  },
  {
    key: "orderStatusMode",
    label: "Order status",
    description: "How the bot handles order/quote status lookups.",
    risk: "Status answers require an authorized customer context; otherwise the conversation is routed to review."
  }
];

function formatEnumLabel(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function buildUpdatePayload(config: BotRuntimeConfig): BotRuntimeConfigUpdate {
  // Send only backend-accepted, safe fields. Unchanged fields are preserved by sending their current
  // value. Connection metadata, revision, timestamps, and tenant id are never sent as mutable fields.
  return {
    enabled: config.enabled,
    greetingEnabled: config.greetingEnabled,
    availabilityCheckEnabled: config.availabilityCheckEnabled,
    priceCheckMode: config.priceCheckMode,
    rfqCaptureMode: config.rfqCaptureMode,
    substituteSuggestionMode: config.substituteSuggestionMode,
    orderStatusMode: config.orderStatusMode,
    unknownCustomerMode: config.unknownCustomerMode,
    humanHandoffEnabled: config.humanHandoffEnabled,
    handoffQueueKey: config.handoffQueueKey,
    inventoryFreshnessMaxMinutes: config.inventoryFreshnessMaxMinutes,
    inventoryFreshnessPolicy: config.inventoryFreshnessPolicy,
    priceVisibilityPolicy: config.priceVisibilityPolicy,
    safeGreetingTemplate: config.safeGreetingTemplate,
    safeFallbackTemplate: config.safeFallbackTemplate,
    handoffTemplate: config.handoffTemplate
  };
}

function configsEqual(a: BotRuntimeConfig | null, b: BotRuntimeConfig | null): boolean {
  if (!a || !b) return a === b;
  return JSON.stringify(buildUpdatePayload(a)) === JSON.stringify(buildUpdatePayload(b));
}

// Hard validation: only rules that are certain from the backend (numeric range). Everything else is a
// non-blocking warning so we never block a combination the backend would actually accept.
function blockingErrors(draft: BotRuntimeConfig): string[] {
  const errors: string[] = [];
  if (
    !Number.isInteger(draft.inventoryFreshnessMaxMinutes) ||
    draft.inventoryFreshnessMaxMinutes < FRESHNESS_MIN ||
    draft.inventoryFreshnessMaxMinutes > FRESHNESS_MAX
  ) {
    errors.push(`Inventory freshness threshold must be a whole number between ${FRESHNESS_MIN} and ${FRESHNESS_MAX} minutes.`);
  }
  return errors;
}

// Advisory warnings mirroring backend rejected combinations. Shown as guidance; the backend remains
// the final authority and will return the authoritative error if a combination is actually invalid.
function policyWarnings(draft: BotRuntimeConfig): string[] {
  const warnings: string[] = [];
  if (draft.priceCheckMode === "DISABLED" && draft.priceVisibilityPolicy !== "NEVER") {
    warnings.push("Price visibility should be NEVER while price check is disabled.");
  }
  if (draft.priceCheckMode === "CONTROLLED_RESPONSE" && draft.priceVisibilityPolicy === "NEVER") {
    warnings.push("A controlled price response needs a visibility policy that allows identified/authorized customers.");
  }
  if (draft.priceCheckMode === "CONTROLLED_RESPONSE" && draft.unknownCustomerMode === "SAFE_GENERIC_REPLY") {
    warnings.push("Unknown customers must never see price; avoid a generic reply when price is a controlled response.");
  }
  const reviewFlows = [draft.priceCheckMode, draft.rfqCaptureMode, draft.substituteSuggestionMode, draft.orderStatusMode];
  if (!draft.humanHandoffEnabled && reviewFlows.includes("OPERATOR_REVIEW_ONLY")) {
    warnings.push("Human handoff must stay enabled while any flow is set to operator-review-only.");
  }
  return warnings;
}

export function BotRuntimeConfigWorkspace({
  initialConnections,
  initialConfig,
  initialError
}: Readonly<{
  initialConnections: BotRuntimeConfigListItem[];
  initialConfig: BotRuntimeConfig | null;
  initialError?: string;
}>) {
  const [selectedConnectionId, setSelectedConnectionId] = useState<string>(
    initialConfig?.channelConnectionId ?? initialConnections[0]?.channelConnectionId ?? ""
  );
  const [savedConfig, setSavedConfig] = useState<BotRuntimeConfig | null>(initialConfig);
  const [draft, setDraft] = useState<BotRuntimeConfig | null>(initialConfig);
  const [action, setAction] = useState<ActionState>({
    status: initialError ? "error" : "idle",
    message: initialError ?? ""
  });
  const [isLoadingConfig, setIsLoadingConfig] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isResetting, setIsResetting] = useState(false);
  const [confirmingReset, setConfirmingReset] = useState(false);

  const isDirty = useMemo(() => !configsEqual(savedConfig, draft), [savedConfig, draft]);
  const errors = useMemo(() => (draft ? blockingErrors(draft) : []), [draft]);
  const warnings = useMemo(() => (draft ? policyWarnings(draft) : []), [draft]);
  const busy = isSaving || isResetting || isLoadingConfig;

  async function selectConnection(connectionId: string) {
    if (!connectionId || connectionId === selectedConnectionId) return;
    setSelectedConnectionId(connectionId);
    setConfirmingReset(false);
    setIsLoadingConfig(true);
    setAction({ status: "loading", message: "Loading configuration..." });
    const result = await getBotRuntimeConfiguration(connectionId);
    setIsLoadingConfig(false);
    if (result.error || !result.data) {
      setSavedConfig(null);
      setDraft(null);
      setAction({ status: "error", message: result.error ?? "Configuration could not be loaded." });
      return;
    }
    setSavedConfig(result.data);
    setDraft(result.data);
    setAction({ status: "idle", message: "" });
  }

  function updateFlowMode(key: ModeFlow["key"], value: string) {
    setDraft((current) => (current ? { ...current, [key]: value } : current));
  }

  function updateGlobalPolicy<K extends keyof BotRuntimeConfig>(key: K, value: BotRuntimeConfig[K]) {
    setDraft((current) => (current ? { ...current, [key]: value } : current));
  }

  async function save() {
    if (!draft || !isDirty || errors.length > 0) return;
    setIsSaving(true);
    setAction({ status: "loading", message: "Saving configuration through the permissioned backend command path..." });
    const result = await updateBotRuntimeConfiguration(draft.channelConnectionId, buildUpdatePayload(draft));
    setIsSaving(false);
    if (result.error || !result.data) {
      // Keep the user's local edits; surface the backend's authoritative error.
      setAction({ status: "error", message: result.error ?? "Configuration was not saved." });
      return;
    }
    setSavedConfig(result.data);
    setDraft(result.data);
    setAction({ status: "success", message: "Configuration saved. Backend runtime and deterministic validation remain the final authority." });
  }

  async function reset() {
    if (!draft) return;
    setConfirmingReset(false);
    setIsResetting(true);
    setAction({ status: "loading", message: "Resetting to safe defaults..." });
    const result = await resetBotRuntimeConfiguration(draft.channelConnectionId);
    setIsResetting(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Configuration was not reset." });
      return;
    }
    setSavedConfig(result.data);
    setDraft(result.data);
    setAction({ status: "success", message: "Configuration reset to safe defaults." });
  }

  if (initialConnections.length === 0) {
    return (
      <section className="empty-state">
        <h2>No bot-capable channel connection found</h2>
        <p>
          Create and activate a messenger channel connection first, then return here to configure controlled bot
          flows. See <a href="/channels">Channels</a> and <a href="/messenger-bridge">Messenger Bridge</a>.
        </p>
      </section>
    );
  }

  return (
    <div className="review-workspace">
      <section className="panel action-panel">
        <h2>Channel connection</h2>
        <div className="button-row">
          <label className="field-label" htmlFor="bot-runtime-connection">Connection</label>
          <select
            id="bot-runtime-connection"
            className="form-input"
            value={selectedConnectionId}
            disabled={busy}
            onChange={(event) => void selectConnection(event.target.value)}
          >
            {initialConnections.map((connection) => (
              <option key={connection.channelConnectionId} value={connection.channelConnectionId}>
                {connection.displayName} — {connection.providerType} ({connection.connectionStatus})
              </option>
            ))}
          </select>
        </div>
        {draft ? (
          <dl className="detail-list">
            <div><dt>Channel</dt><dd>{draft.providerType}</dd></div>
            <div><dt>Connection status</dt><dd>{draft.connectionStatus}</dd></div>
            <div><dt>Verification</dt><dd>{draft.connectionVerificationMode}</dd></div>
            <div><dt>Bot enabled</dt><dd><span className={`status-pill ${draft.enabled ? "done" : "warning"}`}>{draft.enabled ? "Enabled" : "Disabled"}</span></dd></div>
            <div><dt>External execution</dt><dd><span className="status-pill done">{draft.externalExecution}</span></dd></div>
            <div><dt>Revision</dt><dd>{draft.revision}</dd></div>
            <div><dt>Updated</dt><dd>{draft.updatedAt ? new Date(draft.updatedAt).toLocaleString() : "n/a"}</dd></div>
          </dl>
        ) : null}
      </section>

      {action.message ? (
        <section className="panel">
          <p className={`form-message ${action.status === "error" ? "error" : action.status === "success" ? "done" : ""}`}>{action.message}</p>
        </section>
      ) : null}

      {draft ? (
        <>
          <section className="panel">
            <h2>Bot master switch</h2>
            <p className="risk-note">Configuration can only constrain bot behavior. Bot responses are limited to approved flows.</p>
            <label className="check-row">
              <input
                type="checkbox"
                checked={draft.enabled}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("enabled", event.target.checked)}
              />
              <span>Bot enabled for this connection</span>
              <span className={`status-pill ${draft.enabled ? "done" : "warning"}`}>{draft.enabled ? "Enabled" : "Disabled"}</span>
            </label>
          </section>

          <section className="panel table-panel">
            <h2>Flow policy</h2>
            <p className="risk-note">Disabled or restricted flows are denied or routed to operator review by backend policy. RFQ/order work remains draft/review-only.</p>
            <table className="data-table">
              <thead>
                <tr><th>Flow</th><th>Mode</th><th>What it does</th><th>Safety</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td><label htmlFor="flow-greeting">Greeting</label></td>
                  <td>
                    <input
                      id="flow-greeting"
                      type="checkbox"
                      checked={draft.greetingEnabled}
                      disabled={busy}
                      onChange={(event) => updateGlobalPolicy("greetingEnabled", event.target.checked)}
                    />
                    <span className={`status-pill ${draft.greetingEnabled ? "done" : "warning"}`}>{draft.greetingEnabled ? "Enabled" : "Disabled"}</span>
                  </td>
                  <td>Safe greeting reply.</td>
                  <td className="muted-copy">No business data is disclosed.</td>
                </tr>
                <tr>
                  <td><label htmlFor="flow-availability">Availability check</label></td>
                  <td>
                    <input
                      id="flow-availability"
                      type="checkbox"
                      checked={draft.availabilityCheckEnabled}
                      disabled={busy}
                      onChange={(event) => updateGlobalPolicy("availabilityCheckEnabled", event.target.checked)}
                    />
                    <span className={`status-pill ${draft.availabilityCheckEnabled ? "done" : "warning"}`}>{draft.availabilityCheckEnabled ? "Enabled" : "Disabled"}</span>
                  </td>
                  <td>Answer availability questions.</td>
                  <td className="muted-copy">Stale stock is routed to review by freshness policy.</td>
                </tr>
                {MODE_FLOWS.map((flow) => (
                  <tr key={flow.key}>
                    <td><label htmlFor={`flow-${flow.key}`}>{flow.label}</label></td>
                    <td>
                      <select
                        id={`flow-${flow.key}`}
                        className="form-input"
                        value={draft[flow.key]}
                        disabled={busy}
                        onChange={(event) => updateFlowMode(flow.key, event.target.value)}
                      >
                        {FLOW_MODE_OPTIONS.map((mode) => (
                          <option key={mode} value={mode}>{formatEnumLabel(mode)}</option>
                        ))}
                      </select>
                    </td>
                    <td>{flow.description}</td>
                    <td className="muted-copy">{flow.risk}</td>
                  </tr>
                ))}
                <tr>
                  <td><label htmlFor="flow-handoff">Human handoff</label></td>
                  <td>
                    <input
                      id="flow-handoff"
                      type="checkbox"
                      checked={draft.humanHandoffEnabled}
                      disabled={busy}
                      onChange={(event) => updateGlobalPolicy("humanHandoffEnabled", event.target.checked)}
                    />
                    <span className={`status-pill ${draft.humanHandoffEnabled ? "done" : "warning"}`}>{draft.humanHandoffEnabled ? "Enabled" : "Disabled"}</span>
                  </td>
                  <td>Route the conversation to a human review queue.</td>
                  <td className="muted-copy">Handoff routes the conversation to a human review queue.</td>
                </tr>
              </tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Safety policy</h2>
            <div className="control-grid">
              <label className="field-label" htmlFor="policy-price-visibility">Price visibility</label>
              <select
                id="policy-price-visibility"
                className="form-input"
                value={draft.priceVisibilityPolicy}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("priceVisibilityPolicy", event.target.value)}
              >
                {PRICE_VISIBILITY_OPTIONS.map((option) => (
                  <option key={option} value={option}>{formatEnumLabel(option)}</option>
                ))}
              </select>

              <label className="field-label" htmlFor="policy-unknown-customer">Unknown customer handling</label>
              <select
                id="policy-unknown-customer"
                className="form-input"
                value={draft.unknownCustomerMode}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("unknownCustomerMode", event.target.value)}
              >
                {UNKNOWN_CUSTOMER_OPTIONS.map((option) => (
                  <option key={option} value={option}>{formatEnumLabel(option)}</option>
                ))}
              </select>

              <label className="field-label" htmlFor="policy-inventory-freshness">Inventory freshness policy</label>
              <select
                id="policy-inventory-freshness"
                className="form-input"
                value={draft.inventoryFreshnessPolicy}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("inventoryFreshnessPolicy", event.target.value)}
              >
                {INVENTORY_FRESHNESS_OPTIONS.map((option) => (
                  <option key={option} value={option}>{formatEnumLabel(option)}</option>
                ))}
              </select>

              <label className="field-label" htmlFor="policy-freshness-minutes">Inventory freshness threshold (minutes)</label>
              <input
                id="policy-freshness-minutes"
                className="form-input"
                type="number"
                min={FRESHNESS_MIN}
                max={FRESHNESS_MAX}
                value={draft.inventoryFreshnessMaxMinutes}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("inventoryFreshnessMaxMinutes", Number.parseInt(event.target.value, 10))}
              />

              <label className="field-label" htmlFor="policy-handoff-queue">Default handoff queue</label>
              <input
                id="policy-handoff-queue"
                className="form-input"
                type="text"
                value={draft.handoffQueueKey}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("handoffQueueKey", event.target.value)}
              />
            </div>
          </section>

          <section className="panel">
            <h2>Safe response templates</h2>
            <p className="risk-note">Templates are response copy only and are never executed.</p>
            <div className="control-grid">
              <label className="field-label" htmlFor="template-greeting">Greeting template</label>
              <textarea
                id="template-greeting"
                className="form-input"
                rows={2}
                value={draft.safeGreetingTemplate}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("safeGreetingTemplate", event.target.value)}
              />
              <label className="field-label" htmlFor="template-fallback">Fallback template</label>
              <textarea
                id="template-fallback"
                className="form-input"
                rows={2}
                value={draft.safeFallbackTemplate}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("safeFallbackTemplate", event.target.value)}
              />
              <label className="field-label" htmlFor="template-handoff">Handoff template</label>
              <textarea
                id="template-handoff"
                className="form-input"
                rows={2}
                value={draft.handoffTemplate}
                disabled={busy}
                onChange={(event) => updateGlobalPolicy("handoffTemplate", event.target.value)}
              />
            </div>
          </section>

          {warnings.length > 0 ? (
            <section className="panel">
              <h2>Policy warnings</h2>
              <ul className="risk-note">
                {warnings.map((warning) => <li key={warning}>{warning}</li>)}
              </ul>
              <p className="muted-copy">These are advisory. The backend remains the final authority and will reject an invalid combination with a precise message.</p>
            </section>
          ) : null}

          {errors.length > 0 ? (
            <section className="panel">
              <ul className="form-message error">
                {errors.map((error) => <li key={error}>{error}</li>)}
              </ul>
            </section>
          ) : null}

          <section className="panel action-panel">
            <div className="button-row">
              <button
                className="button"
                type="button"
                disabled={!isDirty || busy || errors.length > 0}
                onClick={() => void save()}
              >
                {isSaving ? "Saving..." : "Save configuration"}
              </button>
              <span className={`status-pill ${isDirty ? "warning" : "done"}`}>{isDirty ? "Unsaved changes" : "No changes"}</span>

              {confirmingReset ? (
                <>
                  <span className="muted-copy">Reset all fields to safe defaults?</span>
                  <button className="button" type="button" disabled={busy} onClick={() => void reset()}>
                    {isResetting ? "Resetting..." : "Confirm reset"}
                  </button>
                  <button className="button" type="button" disabled={busy} onClick={() => setConfirmingReset(false)}>Cancel</button>
                </>
              ) : (
                <button className="button" type="button" disabled={busy} onClick={() => setConfirmingReset(true)}>
                  Reset to safe defaults
                </button>
              )}
            </div>
            <p className="risk-note">Saving uses the permissioned backend command path (PUT /api/v1/bot-runtime/configurations).</p>
            <p className="risk-note">External writes require separate integration approval and are not part of this screen.</p>
          </section>
        </>
      ) : null}
    </div>
  );
}
