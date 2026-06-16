// OP-CAP-22 — honest status/evidence badges. These render exactly what the backend reports; the
// frontend never upgrades evidence (e.g. ESTIMATED is never shown as VERIFIED) and never invents
// statuses. Evidence authority order: VERIFIED > MIRRORED > SYSTEM_DERIVED > ESTIMATED > MANUAL > UNKNOWN.

const EVIDENCE_LABELS: Record<string, string> = {
  VERIFIED: "Verified",
  MIRRORED: "Mirrored",
  SYSTEM_DERIVED: "System derived",
  ESTIMATED: "Estimated",
  MANUAL: "Operator entered",
  UNKNOWN: "Unknown"
};

const STATE_LABELS: Record<string, string> = {
  NOT_STARTED: "Not started",
  ACTIVE: "Active",
  COMPLETED: "Completed",
  BLOCKED: "Blocked",
  SKIPPED: "Skipped",
  UNKNOWN: "Unknown"
};

export function EvidenceBadge({ level }: Readonly<{ level: string }>) {
  return <span className="severity-badge" data-evidence={level}>{EVIDENCE_LABELS[level] ?? level}</span>;
}

export function MilestoneStateBadge({ state }: Readonly<{ state: string }>) {
  return <span className="status-pill" data-state={state}>{STATE_LABELS[state] ?? state}</span>;
}

export function BlockedBadge({ blocked }: Readonly<{ blocked: boolean }>) {
  return blocked ? <span className="severity-badge" data-blocked="true">Blocked</span> : <span className="muted-copy">—</span>;
}
