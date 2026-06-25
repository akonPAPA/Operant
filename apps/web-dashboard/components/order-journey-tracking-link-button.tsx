"use client";

import { useState } from "react";

import {
  createOrderJourneyTrackingLink,
  toCustomerTrackingHref,
  toCustomerTrackingPath,
  TrackingLinkCreated
} from "@/lib/order-journey-api";
import {
  mapOperatorActionError,
  OperatorActionResult,
  useOperatorAction
} from "@/lib/operator-action-runtime";
import { TRACKING_LINK_CREATED_EVENT } from "@/lib/order-journey-tracking-link-registry";

// OP-CAP-46D — small operator-facing affordance to mint and copy a one-time customer
// tracking link for an order journey. The button posts to the existing OP-CAP-46C
// endpoint; the backend owns tenant resolution, actor resolution, token minting and
// expiry. The raw token is shown once in this panel and is NEVER persisted (no
// localStorage / sessionStorage), NEVER logged, and NEVER sent to analytics.
type Props = Readonly<{ journeyId: string }>;

// OP-CAP-46F — shown if the backend trackingPath cannot be mapped to a customer page URL. It
// never echoes the raw backend body/path/token; the link itself was minted, so the operator can
// simply retry.
const MALFORMED_LINK_MESSAGE =
  "Tracking link was created, but the customer page URL could not be prepared. Please retry.";

export function OrderJourneyTrackingLinkButton({ journeyId }: Props) {
  const [link, setLink] = useState<TrackingLinkCreated | null>(null);
  const [message, setMessage] = useState("");
  const [messageKind, setMessageKind] = useState<"idle" | "done" | "error" | "copied">("idle");

  const { execute, pending, disabled } = useOperatorAction<TrackingLinkCreated>({
    onSuccess: (data) => {
      setLink(data);
      setMessageKind("done");
      setMessage("Tracking link created. Share it with the customer — it is valid only once and only until the expiry shown below.");
      // Notify the sibling registry to refresh. The event carries ONLY the journeyId — never the
      // token, path, or any link metadata (those stay in this panel's one-time view above).
      if (typeof window !== "undefined") {
        window.dispatchEvent(new CustomEvent(TRACKING_LINK_CREATED_EVENT, { detail: { journeyId } }));
      }
    },
    onError: (_code, safeMessage) => {
      setLink(null);
      setMessageKind("error");
      setMessage(safeMessage);
    }
  });

  async function createLink() {
    setMessage("");
    setMessageKind("idle");
    // The backend mints a fresh one-time token per call by design (each link is a
    // distinct credential), so there is no Idempotency-Key contract for this route.
    // The useOperatorAction ref-based duplicate-click guard prevents concurrent submits.
    await execute(async (): Promise<OperatorActionResult<TrackingLinkCreated>> => {
      try {
        const data = await createOrderJourneyTrackingLink(journeyId);
        return { ok: true, data, safeMessage: "Tracking link created." };
      } catch (error) {
        const err = error as Error & { status?: number };
        const { errorCode, safeMessage } = mapOperatorActionError(err.status ?? 500);
        return { ok: false, errorCode, safeMessage };
      }
    });
  }

  async function copyLink() {
    if (!link) return;
    // OP-CAP-46F — copy the CUSTOMER-FACING frontend page URL, never the backend API path.
    // `window` is read only here (a client-only event handler); when an origin is available the
    // copied value is absolute, otherwise it falls back to the relative customer path. A malformed
    // backend trackingPath yields null — show a safe error rather than copying the API endpoint.
    const origin = typeof window !== "undefined" ? window.location.origin : undefined;
    const shareValue = toCustomerTrackingHref(link.trackingPath, origin);
    if (!shareValue) {
      setMessageKind("error");
      setMessage(MALFORMED_LINK_MESSAGE);
      return;
    }
    // Clipboard API is browser-only and may be blocked (insecure context, permission
    // denied) — guard and surface a safe operator message without leaking the link
    // anywhere else (no localStorage, no console).
    if (typeof navigator === "undefined" || !navigator.clipboard) {
      setMessageKind("error");
      setMessage("Copy is unavailable in this browser. Select the link text to copy it manually.");
      return;
    }
    try {
      await navigator.clipboard.writeText(shareValue);
      setMessageKind("copied");
      setMessage("Link copied to clipboard.");
    } catch {
      setMessageKind("error");
      setMessage("Could not copy automatically. Select the link text to copy it manually.");
    }
  }

  // OP-CAP-46F — display the customer-facing frontend page path, never the backend API endpoint.
  // Relative (origin-free) so it is hydration-stable; the absolute href is built at copy time.
  const customerTrackingPath = link ? toCustomerTrackingPath(link.trackingPath) : null;

  return (
    <section className="panel">
      <div className="status-row">
        <h2>Customer tracking link</h2>
      </div>
      <p className="muted-copy">
        Mint a one-time customer-safe link for this order journey. The link reveals only the
        customer-visible status and milestones — never internal status, risk level, or audit
        details. Share it once and refresh this panel to mint a new one.
      </p>
      <div className="upload-form">
        <button className="button" type="button" disabled={disabled} onClick={createLink}>
          {pending ? "Creating tracking link..." : link ? "Create a new tracking link" : "Create tracking link"}
        </button>
      </div>
      {message ? (
        <p className={messageKind === "error" ? "form-message error" : "form-message done"}>{message}</p>
      ) : null}
      {link ? (
        <div className="result-panel">
          {customerTrackingPath ? (
            <>
              <dl className="detail-list">
                <div>
                  <dt>Customer tracking page</dt>
                  <dd>
                    <code data-testid="customer-tracking-path">{customerTrackingPath}</code>
                  </dd>
                </div>
                <div>
                  <dt>Expires at</dt>
                  <dd>{formatExpiry(link.expiresAt)}</dd>
                </div>
              </dl>
              <div className="upload-form">
                <button className="button" type="button" onClick={copyLink}>Copy link</button>
              </div>
              <p className="risk-note">
                Read-only and customer-safe. Do not paste internal-only notes or status into the
                shared message. The token is shown once; close this panel and a new link must be
                minted.
              </p>
            </>
          ) : (
            <p className="form-message error">{MALFORMED_LINK_MESSAGE}</p>
          )}
        </div>
      ) : null}
    </section>
  );
}

function formatExpiry(value: string): string {
  // Render an honest ISO-8601 timestamp; backend remains the authority for expiry.
  // Fall through to the raw value if Date parsing fails — never fabricate a future date.
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toISOString();
}
