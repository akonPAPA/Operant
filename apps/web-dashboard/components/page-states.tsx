// WP2/WP4 — shared page-state language for every operator surface.
//
// One vocabulary so loading, empty, error, unavailable, and access-denied read the same everywhere.
// These are presentational server components: they render the state they are given and never fetch,
// mutate, retry, or claim backend authority. Callers pass a real control for any action (e.g. a retry
// link or a command <form>); these primitives never fabricate an active mutation control, and error
// descriptions must be caller-sanitized (no raw URL / stack / payload).

import type { ReactNode } from "react";

/** Decorative shimmer placeholder. aria-hidden — never announced to assistive tech. */
export function Skeleton({
  width = "100%",
  height = 14,
  radius
}: Readonly<{ width?: number | string; height?: number | string; radius?: number | string }>) {
  const style = {
    width: typeof width === "number" ? `${width}px` : width,
    height: typeof height === "number" ? `${height}px` : height,
    ...(radius !== undefined ? { borderRadius: typeof radius === "number" ? `${radius}px` : radius } : {})
  };
  return <span className="skeleton" style={style} aria-hidden="true" />;
}

/** Loading state. Polite live region so the label is announced once, then replaced by content. */
export function LoadingState({
  label = "Loading…"
}: Readonly<{ label?: string }>) {
  return (
    <div className="page-state" role="status" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      <Skeleton width="40%" height={16} />
      <Skeleton width="90%" />
      <Skeleton width="75%" />
    </div>
  );
}

/** Empty (successful, no records) — visually and semantically distinct from an error. */
export function EmptyState({
  title,
  description,
  note
}: Readonly<{ title: string; description: string; note?: string }>) {
  return (
    <div className="empty-state" role="status">
      <h2>{title}</h2>
      <p>{description}</p>
      {note ? <p className="risk-note">{note}</p> : null}
    </div>
  );
}

/** Error — an operation failed. role="alert"; caller-supplied, sanitized description and optional retry. */
export function ErrorState({
  title = "Something went wrong",
  description,
  action
}: Readonly<{ title?: string; description: string; action?: ReactNode }>) {
  return (
    <div className="page-state page-state--error" role="alert">
      <div className="page-state-head">
        <span aria-hidden="true">✕</span>
        <h2>{title}</h2>
      </div>
      <p>{description}</p>
      {action ? <div className="page-state-actions">{action}</div> : null}
    </div>
  );
}

/** Unavailable — the feature is not part of the current product surface (or is coming later). */
export function UnavailableState({
  title = "Not available yet",
  description,
  reason
}: Readonly<{ title?: string; description: string; reason?: string }>) {
  return (
    <div className="page-state page-state--unavailable" role="status">
      <div className="page-state-head">
        <span aria-hidden="true">◔</span>
        <h2>{title}</h2>
      </div>
      <p>{description}</p>
      {reason ? <p className="risk-note">{reason}</p> : null}
    </div>
  );
}

/** Access denied — distinct from unavailable. The backend authoritatively denied this actor. */
export function AccessDeniedState({
  title = "Access not permitted",
  description = "Your access does not include this area. If you believe this is an error, contact your administrator."
}: Readonly<{ title?: string; description?: string }>) {
  return (
    <div className="page-state page-state--denied" role="status">
      <div className="page-state-head">
        <span aria-hidden="true">⦸</span>
        <h2>{title}</h2>
      </div>
      <p>{description}</p>
    </div>
  );
}
