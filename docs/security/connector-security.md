# Connector Security

Stage 13 makes the Stage 12 connector foundation safer to pilot. The default posture remains conservative: tenant-scoped, DRAFT until activated, READ_ONLY where applicable, and no external writes.

## Secret Handling

- Raw connector secrets must not be returned by API responses, logged, stored in audit event metadata, or exposed to the frontend.
- `SecretVaultService` is the backend abstraction for connector secret storage.
- `LocalDevelopmentSecretVaultService` is for local/dev only. It returns a safe `SecretReference` and keeps raw values out of DTOs.
- Connection tables store secret metadata only: `secret_reference_id` and `secret_last_updated_at`.
- API responses expose only `secretConfigured`, `secretLastUpdatedAt`, and a masked `secretReferenceId`.
- Production should replace the local vault with AWS Secrets Manager, Azure Key Vault, GCP Secret Manager, HashiCorp Vault, or an equivalent managed vault.

## Webhook Verification

Channel webhooks use `ChannelWebhookVerifier` and provider-specific verifier stubs for Telegram, WhatsApp, Meta Messenger, Viber, and WeChat.

Verification modes:
- `DISABLED_FOR_LOCAL_DEV`: explicit local/test relaxation.
- `SHARED_SECRET`: requires a shared-secret style header.
- `SIGNATURE_HEADER`: requires a provider signature header.
- `PROVIDER_SPECIFIC`: adapter-ready mode for provider-specific verification.

Disabled or non-active channel connections reject webhooks before normalization. Invalid verification rejects the webhook and records `CHANNEL_WEBHOOK_VERIFICATION_FAILED`. Accepted webhooks are normalized into `inbound_channel_event` only and record verification status/reason.

## Diagnostics

Health checks return structured diagnostics with safe UI messages:
- `SECRET_MISSING`
- `WEBHOOK_NOT_CONFIGURED`
- `WEBHOOK_VERIFICATION_DISABLED`
- `PROVIDER_UNREACHABLE`
- `READ_ONLY_MODE`
- `WRITE_MODE_NOT_ALLOWED`
- `LAST_SYNC_FAILED`
- `PERMISSION_INSUFFICIENT`
- `TOKEN_EXPIRED`
- `CONFIG_INVALID`

Diagnostics must not include raw tokens, passwords, API keys, webhook secrets, or provider payload secrets.

## Read-Only Connector Pilot

Stage 13 includes a demo ERP read-only pilot adapter. It can return item counts for products, customers, inventory, and prices through `connector_sync_event`, but writes `recordsWritten = 0` and does not update master data.

## Local Verification

Backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test '-Dtest=SecretVaultServiceTest,WebhookVerifierTest,ChannelWebhookSecurityTest,ChannelConnectionDiagnosticsTest,IntegrationConnectionDiagnosticsTest,ReadOnlyConnectorPilotTest,ConnectorSyncSecurityTest,Stage13MigrationFileTest'
```

Frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run typecheck
npm test
npm run build
```

Known limitation: provider cryptographic signature validation is still adapter-ready, not production-certified. External writes remain out of scope.
