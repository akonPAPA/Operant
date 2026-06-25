"use client";

import { useState } from "react";

import {
  createOrderJourneyTrackingLink,
  TrackingLinkCreated
} from "@/lib/order-journey-api";
import {
  mapOperatorActionError,
  OperatorActionResult,
  useOperatorAction
} from "@/lib/operator-action-runtime";

// OP-CAP-46D — small operator-facing affordance to mint and copy a one-time customer
// tracking link for an order journey. The button posts to the existing OP-CAP-46C
// endpoint; the backend owns tenant resolution, actor resolution, token minting and
// expiry. The raw token is shown once in this panel and is NEVER persisted (no
// localStorage / sessionStorage), NEVER logged, and NEVER sent to analytics.
type Props = Readonly<{ journeyId: string }>;

export function OrderJourneyTrackingLinkButton({ journeyId }: Props) {
  const [link, setLink] = useState<TrackingLinkCreated | null>(null);
  const [message, setMessage] = useState("");
  const [messageKind, setMessageKind] = useState<"idle" | "done" | "error" | "copied">("idle");

  const { execute, pending, disabled } = useOperatorAction<TrackingLinkCreated>({
    onSuccess: (data) => {
      setLink(data);
      setMessageKind("done");
      setMessage("Tracking link created. Share it with the customer — it is valid only once and only until the expiry shown below.");
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
    // Clipboard API is browser-only and may be blocked (insecure context, permission
    // denied) — guard and surface a safe operator message without leaking the link
    // anywhere else (no localStorage, no console).
    if (typeof navigator === "undefined" || !navigator.clipboard) {
      setMessageKind("error");
      setMessage("Copy is unavailable in this browser. Select the link text to copy it manually.");
      return;
    }
    try {
      await navigator.clipboard.writeText(link.trackingPath);
      setMessageKind("copied");
      setMessage("Link copied to clipboard.");
    } catch {
      setMessageKind("error");
      setMessage("Could not copy automatically. Select the link text to copy it manually.");
    }
  }

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
          <dl className="detail-list">
            <div>
              <dt>Tracking path</dt>
              <dd>
                <code data-testid="tracking-link-path">{link.trackingPath}</code>
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
