"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { mapOperatorActionError } from "@/lib/operator-action-runtime";
import {
  listOrderJourneyTrackingLinks,
  revokeOrderJourneyTrackingLink,
  TRACKING_LINK_CREATED_EVENT,
  TrackingLinkSummary
} from "@/lib/order-journey-tracking-link-registry";

// OP-CAP-46H — operator "Tracking links" registry panel for an order journey. It lists the journey's
// secure tracking links (safe lifecycle metadata only) and lets an operator revoke an ACTIVE link.
//
// Data boundary: it shows only status / createdAt / expiresAt / revokedAt — never the raw token, its
// hash, the public tracking path, or the customer URL (the list contract carries none of these). It
// performs no order-state / ETA / milestone mutation; the only mutation is the existing OP-CAP-46G
// revoke. Nothing is persisted client-side and nothing is logged.
type Props = Readonly<{ journeyId: string }>;

export function OrderJourneyTrackingLinkRegistry({ journeyId }: Props) {
  const [links, setLinks] = useState<TrackingLinkSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [messageKind, setMessageKind] = useState<"idle" | "done" | "error">("idle");
  // Which link ids currently have a revoke in flight — drives per-row disabling and prevents
  // duplicate concurrent submissions (ref mirror avoids stale-closure double-submits).
  const [revoking, setRevoking] = useState<readonly string[]>([]);
  const revokingRef = useRef<Set<string>>(new Set());

  // Apply a finished list fetch. Pulled out so the mount effect can set state only AFTER its await
  // (never synchronously in the effect body), matching the repo's load-on-mount pattern.
  const apply = useCallback((data: { links: TrackingLinkSummary[] } | null, loadError?: string) => {
    if (!data) {
      setLinks(null);
      setError(loadError ?? "Tracking links could not be loaded.");
    } else {
      setLinks(data.links);
      setError("");
    }
    setLoading(false);
  }, []);

  // Imperative refresh for event handlers (Refresh button, post-create event, post-revoke) — never
  // called directly from an effect body, so the leading setLoading is safe.
  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    const { data, error: loadError } = await listOrderJourneyTrackingLinks(journeyId);
    apply(data, loadError);
  }, [journeyId, apply]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const { data, error: loadError } = await listOrderJourneyTrackingLinks(journeyId);
      if (!cancelled) {
        apply(data, loadError);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [journeyId, apply]);

  // Refresh when the sibling create affordance mints a new link for THIS journey.
  useEffect(() => {
    function onCreated(event: Event) {
      const detail = (event as CustomEvent<{ journeyId?: string }>).detail;
      if (detail?.journeyId === journeyId) {
        void reload();
      }
    }
    if (typeof window === "undefined") {
      return;
    }
    window.addEventListener(TRACKING_LINK_CREATED_EVENT, onCreated);
    return () => window.removeEventListener(TRACKING_LINK_CREATED_EVENT, onCreated);
  }, [journeyId, reload]);

  async function revoke(linkId: string) {
    // Duplicate-submit guard — ref-based so a rapid double-click never fires two revokes.
    if (revokingRef.current.has(linkId)) {
      return;
    }
    revokingRef.current.add(linkId);
    setRevoking((current) => [...current, linkId]);
    setMessage("");
    setMessageKind("idle");
    try {
      await revokeOrderJourneyTrackingLink(journeyId, linkId);
      setMessageKind("done");
      setMessage("Tracking link revoked. The shared link no longer works for the customer.");
      await reload();
    } catch (err) {
      const status = (err as { status?: number }).status ?? 500;
      const { safeMessage } = mapOperatorActionError(status);
      setMessageKind("error");
      setMessage(safeMessage);
    } finally {
      revokingRef.current.delete(linkId);
      setRevoking((current) => current.filter((id) => id !== linkId));
    }
  }

  return (
    <section className="panel">
      <div className="status-row">
        <h2>Tracking links</h2>
        <button className="button" type="button" onClick={() => void reload()} disabled={loading}>
          {loading ? "Loading..." : "Refresh"}
        </button>
      </div>
      <p className="muted-copy">
        Secure customer tracking links minted for this order journey. Revoke an active link to stop it
        working immediately — the customer simply sees the same generic &ldquo;not available&rdquo;
        result as an expired link. Revoking does not change the order, its status, or its milestones.
      </p>
      {message ? (
        <p className={messageKind === "error" ? "form-message error" : "form-message done"}>{message}</p>
      ) : null}
      {error ? (
        <p className="muted-copy">{error}</p>
      ) : loading && !links ? (
        <p className="muted-copy">Loading tracking links...</p>
      ) : !links || links.length === 0 ? (
        <p className="muted-copy">No tracking links have been created for this journey yet.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Created</th>
              <th>Expires</th>
              <th>Revoked</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {links.map((link) => {
              const inFlight = revoking.includes(link.linkId);
              const isActive = link.status === "ACTIVE";
              return (
                <tr key={link.linkId}>
                  <td>
                    <span className={statusClass(link.status)}>{statusLabel(link.status)}</span>
                  </td>
                  <td>{formatTimestamp(link.createdAt)}</td>
                  <td>{formatTimestamp(link.expiresAt)}</td>
                  <td>{link.revokedAt ? formatTimestamp(link.revokedAt) : "—"}</td>
                  <td>
                    {isActive ? (
                      <button
                        className="button"
                        type="button"
                        disabled={inFlight}
                        onClick={() => void revoke(link.linkId)}
                      >
                        {inFlight ? "Revoking..." : "Revoke"}
                      </button>
                    ) : (
                      <span className="muted-copy">
                        {link.status === "REVOKED" ? "Revoked" : "Expired"}
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}

function statusLabel(status: TrackingLinkSummary["status"]): string {
  switch (status) {
    case "ACTIVE":
      return "Active";
    case "EXPIRED":
      return "Expired";
    case "REVOKED":
      return "Revoked";
    default:
      return status;
  }
}

function statusClass(status: TrackingLinkSummary["status"]): string {
  return status === "ACTIVE" ? "badge badge-ok" : "badge badge-muted";
}

function formatTimestamp(value: string): string {
  // Honest ISO-8601 rendering; the backend remains the authority. Never fabricate a date.
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toISOString();
}
