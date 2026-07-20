// WP2 — accessible StatusBadge primitive.
//
// Status is conveyed by an icon glyph AND text, never by color alone (WCAG 1.4.1). The tone only
// styles the badge; it makes no authorization claim. This is a presentational server component.

export type StatusTone = "neutral" | "success" | "warning" | "danger" | "info";

const toneGlyph: Readonly<Record<StatusTone, string>> = {
  neutral: "•",
  success: "✓",
  warning: "!",
  danger: "✕",
  info: "i"
};

const toneWord: Readonly<Record<StatusTone, string>> = {
  neutral: "Status",
  success: "Success",
  warning: "Warning",
  danger: "Error",
  info: "Information"
};

export function StatusBadge({
  tone = "neutral",
  label
}: Readonly<{ tone?: StatusTone; label: string }>) {
  const className = tone === "neutral" ? "status-badge" : `status-badge status-badge--${tone}`;
  return (
    <span className={className} data-tone={tone}>
      <span className="status-badge-glyph" aria-hidden="true">
        {toneGlyph[tone]}
      </span>
      <span className="visually-hidden">{toneWord[tone]}: </span>
      <span>{label}</span>
    </span>
  );
}
