# Workspace Timeline

The Stage 6 timeline combines:

- `AuditEvent`
- `OperatorAction`
- `ApprovalDecision`
- `WorkspaceNote`

The first implementation exposes operator actions, approval decisions, and notes through a lightweight timeline DTO. This is not complex event sourcing; it is an explainable operator review trail.
