export function EmptyState({ title, description }: Readonly<{ title: string; description: string }>) {
  return (
    <div className="empty-state">
      <h2>{title}</h2>
      <p>{description}</p>
      <p className="risk-note">
        Stage 1 exposes read-only shell screens only. Future business mutations must call core-api command services.
      </p>
    </div>
  );
}