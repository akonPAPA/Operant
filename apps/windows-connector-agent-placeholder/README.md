# Windows Connector Agent Placeholder

The Windows connector is planned for later stages. It will support local Excel/CSV folders, 1C installations, local databases, and on-prem systems where a cloud service should not receive inbound network access.

Security requirements:

- Use outbound-only HTTPS from the client environment to OrderPilot.
- Use scoped credentials and tenant-bound connector identity.
- Be read-only by default.
- Never expose local databases publicly.
- Never use unrestricted admin credentials.
- External writes remain disabled until the future ChangeRequest and approval model exists.