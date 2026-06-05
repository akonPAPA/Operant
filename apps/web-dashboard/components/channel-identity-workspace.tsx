"use client";

import { useState } from "react";
import {
  blockChannelIdentity,
  formatSenderId,
  getChannelIdentity,
  linkChannelIdentity,
  listCustomerAccounts,
  listCustomerContacts,
  markNeedsReview,
  resolutionStatusClass,
  resolutionStatusLabel,
  unlinkChannelIdentity,
  type ChannelIdentity,
  type CustomerAccountSummary,
  type CustomerContactSummary
} from "@/lib/channel-identity-api";

// Exact domain status values from backend — do not rename.
const STATUS_FILTERS = ["all", "LINKED", "UNLINKED", "BLOCKED", "NEEDS_REVIEW", "SUGGESTED_MATCH"] as const;
type StatusFilter = (typeof STATUS_FILTERS)[number];

type ActionStatus = "idle" | "loading" | "success" | "error";
type ActionState = { status: ActionStatus; message: string };

function formatTimestamp(ts?: string): string {
  if (!ts) return "n/a";
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return ts;
  }
}

function statusFilterLabel(s: StatusFilter): string {
  if (s === "all") return "All";
  const labels: Record<string, string> = {
    LINKED: "Linked",
    UNLINKED: "Unlinked",
    BLOCKED: "Blocked",
    NEEDS_REVIEW: "Needs review",
    SUGGESTED_MATCH: "Suggested match"
  };
  return labels[s] ?? s;
}

export function ChannelIdentityWorkspace({
  initialIdentities,
  initialError
}: Readonly<{
  initialIdentities: ChannelIdentity[];
  initialError?: string;
}>) {
  const [identities, setIdentities] = useState<ChannelIdentity[]>(initialIdentities);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [selectedId, setSelectedId] = useState<string | null>(
    initialIdentities[0]?.id ?? null
  );

  // Detail/action state
  const [detail, setDetail] = useState<ChannelIdentity | null>(
    initialIdentities[0] ?? null
  );
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);

  // Link dialog
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [accounts, setAccounts] = useState<CustomerAccountSummary[]>([]);
  const [contacts, setContacts] = useState<CustomerContactSummary[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState("");
  const [selectedContactId, setSelectedContactId] = useState("");
  const [linkNotes, setLinkNotes] = useState("");
  const [isLoadingAccounts, setIsLoadingAccounts] = useState(false);
  const [isLoadingContacts, setIsLoadingContacts] = useState(false);

  // Confirmations
  const [confirmUnlink, setConfirmUnlink] = useState(false);
  const [confirmBlock, setConfirmBlock] = useState(false);

  // Mutation action state
  const [action, setAction] = useState<ActionState>({
    status: initialError ? "error" : "idle",
    message: initialError ?? ""
  });
  const [isMutating, setIsMutating] = useState(false);

  const busy = isMutating || isLoadingDetail;

  const filteredIdentities =
    statusFilter === "all"
      ? identities
      : identities.filter((i) => i.identityStatus === statusFilter);

  async function selectIdentity(id: string) {
    if (id === selectedId) return;
    setSelectedId(id);
    setConfirmUnlink(false);
    setConfirmBlock(false);
    setShowLinkDialog(false);
    setAction({ status: "idle", message: "" });
    setIsLoadingDetail(true);
    const result = await getChannelIdentity(id);
    setIsLoadingDetail(false);
    if (result.error || !result.data) {
      setDetail(null);
      setAction({ status: "error", message: result.error ?? "Could not load identity detail." });
      return;
    }
    setDetail(result.data);
  }

  function refreshIdentityInList(updated: ChannelIdentity) {
    setIdentities((prev) => prev.map((i) => (i.id === updated.id ? updated : i)));
    setDetail(updated);
    setAction({ status: "success", message: "Identity updated." });
  }

  // --- Link dialog ---

  async function openLinkDialog() {
    setShowLinkDialog(true);
    setSelectedAccountId("");
    setSelectedContactId("");
    setLinkNotes("");
    setContacts([]);
    setAction({ status: "idle", message: "" });
    setIsLoadingAccounts(true);
    const result = await listCustomerAccounts();
    setIsLoadingAccounts(false);
    if (result.error) {
      setAction({ status: "error", message: result.error });
      return;
    }
    setAccounts(result.data.filter((a) => a.status === "ACTIVE" || a.status === "active"));
  }

  async function onAccountSelected(accountId: string) {
    setSelectedAccountId(accountId);
    setSelectedContactId("");
    setContacts([]);
    if (!accountId) return;
    setIsLoadingContacts(true);
    const result = await listCustomerContacts(accountId);
    setIsLoadingContacts(false);
    if (!result.error) {
      setContacts(result.data.filter((c) => c.active));
    }
  }

  function onContactSelected(contactId: string) {
    setSelectedContactId(contactId);
    // When contact is selected, its account is already selected — mismatch is prevented by
    // only showing contacts for the chosen account. No cross-account contact can be selected here.
  }

  async function submitLink() {
    if (!detail || (!selectedAccountId && !selectedContactId)) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Linking identity through the permissioned backend command..." });
    const req =
      selectedContactId
        ? // Contact selected: send both contact and the validated account (prevents mismatch)
          { customerAccountId: selectedAccountId || undefined, customerContactId: selectedContactId, notes: linkNotes || undefined }
        : // Account-only link
          { customerAccountId: selectedAccountId, notes: linkNotes || undefined };
    const result = await linkChannelIdentity(detail.id, req);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({
        status: "error",
        message: result.error ?? "Link command was not accepted by the backend."
      });
      return;
    }
    setShowLinkDialog(false);
    refreshIdentityInList(result.data);
  }

  // --- Unlink ---

  async function submitUnlink() {
    if (!detail) return;
    setConfirmUnlink(false);
    setIsMutating(true);
    setAction({ status: "loading", message: "Unlinking identity..." });
    const result = await unlinkChannelIdentity(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Unlink was not accepted by the backend." });
      return;
    }
    refreshIdentityInList(result.data);
  }

  // --- Block ---

  async function submitBlock() {
    if (!detail) return;
    setConfirmBlock(false);
    setIsMutating(true);
    setAction({ status: "loading", message: "Blocking sender..." });
    const result = await blockChannelIdentity(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Block was not accepted by the backend." });
      return;
    }
    refreshIdentityInList(result.data);
  }

  // --- Mark needs-review ---

  async function submitNeedsReview() {
    if (!detail) return;
    setIsMutating(true);
    setAction({ status: "loading", message: "Marking as needs review..." });
    const result = await markNeedsReview(detail.id);
    setIsMutating(false);
    if (result.error || !result.data) {
      setAction({ status: "error", message: result.error ?? "Mark needs-review was not accepted by the backend." });
      return;
    }
    refreshIdentityInList(result.data);
  }

  const resStatus = detail?.identityResolution?.status;

  return (
    <div className="review-workspace">

      {/* Feedback banner */}
      {action.message ? (
        <section className="panel">
          <p
            className={`form-message ${
              action.status === "error"
                ? "error"
                : action.status === "success"
                ? "done"
                : ""
            }`}
          >
            {action.message}
          </p>
        </section>
      ) : null}

      {/* List panel */}
      <section className="panel table-panel">
        <div className="button-row">
          <h2>Channel identities</h2>
          <label htmlFor="ci-status-filter" className="field-label">Filter by status</label>
          <select
            id="ci-status-filter"
            className="form-input"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
          >
            {STATUS_FILTERS.map((s) => (
              <option key={s} value={s}>{statusFilterLabel(s)}</option>
            ))}
          </select>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Channel</th>
              <th>Sender</th>
              <th>Display name</th>
              <th>Resolution</th>
              <th>Linked customer</th>
              <th>Updated</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {filteredIdentities.length === 0 ? (
              <tr>
                <td colSpan={7}>
                  {identities.length === 0
                    ? "No channel identities yet. Inbound messages create sender identity rows automatically."
                    : "No identities match the selected filter."}
                </td>
              </tr>
            ) : (
              filteredIdentities.map((identity) => {
                const rs = identity.identityResolution?.status;
                return (
                  <tr
                    key={identity.id}
                    className={selectedId === identity.id ? "selected-row" : ""}
                  >
                    <td>{identity.channelType}</td>
                    <td>{formatSenderId(identity.externalSenderId)}</td>
                    <td>{identity.senderDisplayName ?? "n/a"}</td>
                    <td>
                      <span className={`status-pill ${resolutionStatusClass(rs)}`}>
                        {resolutionStatusLabel(rs)}
                      </span>
                    </td>
                    <td>{identity.customerAccountId ? identity.customerAccountId.slice(0, 8) + "…" : "—"}</td>
                    <td>{formatTimestamp(identity.updatedAt)}</td>
                    <td>
                      <button
                        className="button"
                        type="button"
                        disabled={busy}
                        onClick={() => void selectIdentity(identity.id)}
                      >
                        Review
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </section>

      {/* Detail / action panel */}
      {isLoadingDetail ? (
        <section className="panel">
          <p className="muted-copy">Loading identity detail…</p>
        </section>
      ) : detail ? (
        <section className="panel action-panel">
          <h2>Identity detail</h2>
          <dl className="detail-list">
            <div><dt>Channel</dt><dd>{detail.channelType}</dd></div>
            <div>
              <dt>External sender ID</dt>
              <dd>{formatSenderId(detail.externalSenderId)}</dd>
            </div>
            {detail.senderDisplayName ? (
              <div><dt>Display name</dt><dd>{detail.senderDisplayName}</dd></div>
            ) : null}
            <div>
              <dt>Resolution status</dt>
              <dd>
                <span className={`status-pill ${resolutionStatusClass(resStatus)}`}>
                  {resolutionStatusLabel(resStatus)}
                </span>
                {detail.identityResolution?.reason ? (
                  <span className="muted-copy"> — {detail.identityResolution.reason}</span>
                ) : null}
              </dd>
            </div>
            <div><dt>Domain status</dt><dd>{detail.identityStatus}</dd></div>
            {detail.customerAccountId ? (
              <div>
                <dt>Linked account</dt>
                <dd>{detail.customerAccountId}</dd>
              </div>
            ) : null}
            {detail.customerContactId ? (
              <div>
                <dt>Linked contact</dt>
                <dd>{detail.customerContactId}</dd>
              </div>
            ) : null}
            {detail.linkedAt ? (
              <div><dt>Linked at</dt><dd>{formatTimestamp(detail.linkedAt)}</dd></div>
            ) : null}
            {detail.notes ? (
              <div><dt>Notes</dt><dd>{detail.notes}</dd></div>
            ) : null}
            <div><dt>Created</dt><dd>{formatTimestamp(detail.createdAt)}</dd></div>
            <div><dt>Updated</dt><dd>{formatTimestamp(detail.updatedAt)}</dd></div>
          </dl>

          {/* Action row */}
          <div className="button-row">
            <button
              className="button"
              type="button"
              disabled={busy}
              onClick={() => void openLinkDialog()}
            >
              Link to customer
            </button>

            {!confirmUnlink ? (
              <button
                className="button"
                type="button"
                disabled={busy || detail.identityStatus === "UNLINKED"}
                onClick={() => { setConfirmUnlink(true); setConfirmBlock(false); }}
              >
                Unlink
              </button>
            ) : (
              <>
                <span className="muted-copy">Unlink this sender from the current customer?</span>
                <button className="button" type="button" disabled={busy} onClick={() => void submitUnlink()}>
                  {isMutating ? "Unlinking…" : "Confirm unlink"}
                </button>
                <button className="button" type="button" disabled={busy} onClick={() => setConfirmUnlink(false)}>Cancel</button>
              </>
            )}

            {!confirmBlock ? (
              <button
                className="button"
                type="button"
                disabled={busy || detail.identityStatus === "BLOCKED"}
                onClick={() => { setConfirmBlock(true); setConfirmUnlink(false); }}
              >
                Block
              </button>
            ) : (
              <>
                <span className="muted-copy risk-note">
                  Blocked senders will not receive business answers through the bot runtime.
                </span>
                <button className="button" type="button" disabled={busy} onClick={() => void submitBlock()}>
                  {isMutating ? "Blocking…" : "Confirm block"}
                </button>
                <button className="button" type="button" disabled={busy} onClick={() => setConfirmBlock(false)}>Cancel</button>
              </>
            )}

            <button
              className="button"
              type="button"
              disabled={busy || detail.identityStatus === "NEEDS_REVIEW"}
              onClick={() => { setConfirmUnlink(false); setConfirmBlock(false); void submitNeedsReview(); }}
            >
              {isMutating ? "Marking…" : "Mark needs review"}
            </button>
          </div>

          <p className="risk-note">
            All mutations use the permissioned backend command path (CHANNEL_IDENTITY_ACTION).
            The backend re-validates tenant scope, customer/contact ownership, and identity status on every request.
            Identity decisions do not auto-approve quotes, orders, or discounts, and never bypass bot policy or runtime validation.
          </p>
        </section>
      ) : null}

      {/* Link dialog */}
      {showLinkDialog && detail ? (
        <section className="panel action-panel">
          <h2>Link to customer account</h2>
          <p className="muted-copy">
            Select a customer account. Optionally select a contact once the account loads.
            Account and contact must belong to this tenant — the backend validates ownership before persisting.
          </p>

          {isLoadingAccounts ? (
            <p className="muted-copy">Loading customer accounts…</p>
          ) : (
            <div className="control-grid">
              <label className="field-label" htmlFor="link-account">Customer account</label>
              <select
                id="link-account"
                className="form-input"
                value={selectedAccountId}
                disabled={busy}
                onChange={(e) => void onAccountSelected(e.target.value)}
              >
                <option value="">— Select account —</option>
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.accountCode} — {a.legalName}
                  </option>
                ))}
              </select>

              {selectedAccountId ? (
                <>
                  <label className="field-label" htmlFor="link-contact">Contact (optional)</label>
                  {isLoadingContacts ? (
                    <p className="muted-copy">Loading contacts…</p>
                  ) : (
                    <select
                      id="link-contact"
                      className="form-input"
                      value={selectedContactId}
                      disabled={busy}
                      onChange={(e) => onContactSelected(e.target.value)}
                    >
                      <option value="">— No contact (account-only link) —</option>
                      {contacts.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.fullName} ({c.contactType}){c.preferred ? " ★" : ""}
                        </option>
                      ))}
                    </select>
                  )}
                </>
              ) : null}

              <label className="field-label" htmlFor="link-notes">Notes (optional)</label>
              <input
                id="link-notes"
                className="form-input"
                type="text"
                placeholder="e.g. operator-confirmed via phone"
                value={linkNotes}
                disabled={busy}
                onChange={(e) => setLinkNotes(e.target.value)}
              />
            </div>
          )}

          <div className="button-row">
            <button
              className="button"
              type="button"
              disabled={busy || !selectedAccountId}
              onClick={() => void submitLink()}
            >
              {isMutating ? "Linking…" : "Confirm link"}
            </button>
            <button
              className="button"
              type="button"
              disabled={busy}
              onClick={() => { setShowLinkDialog(false); setAction({ status: "idle", message: "" }); }}
            >
              Cancel
            </button>
          </div>
          <p className="risk-note">
            Linking is an explicit operator action. No auto-linking occurs from inbound messages or bot/AI flows.
            The backend validates tenant ownership of the selected account and contact before persisting.
          </p>
        </section>
      ) : null}
    </div>
  );
}
