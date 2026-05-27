# Connector Credential Boundary

Stage 9B does not handle real connector secrets.

The only supported credential state is a placeholder reference:

- `NOT_CONFIGURED`
- `CONFIGURED_PLACEHOLDER`
- `REVOKED`

Rules:

- Do not store secret values in the database.
- Do not log secret values.
- Do not require real environment credentials for Demo ERP.
- Use masked placeholder references in APIs and UI.
- Production credentials must use an approved secrets manager in a later security-accepted phase.

Any production ERP/1C connector implementation must pass security review before it can leave `WRITE_DISABLED` or `DEMO_ONLY` mode.
