# Stage 13 - Connector Security & Provider Onboarding Foundation

## Scope

Stage 13 hardens the Stage 12 channel/integration foundation for secure read-only connector pilots. It adds secret metadata, explicit webhook verification modes, structured diagnostics, audit events, and a demo ERP read-only sync pilot.

## Implemented

- `SecretVaultService`, `LocalDevelopmentSecretVaultService`, and `SecretReference`.
- Secret metadata columns for channel and integration connections.
- Provider webhook verifier framework for Telegram, WhatsApp, Meta Messenger, Viber, and WeChat.
- Verification status/reason on inbound channel events.
- Structured connection diagnostics for channel and integration health checks.
- Demo ERP read-only pilot counts for product, customer, inventory, and price imports.
- Sync duration and error category metadata.
- Dashboard copy for health, diagnostics, verification, and read-only pilot constraints.
- Stage 13 migration: `V21__connector_security_provider_onboarding.sql`.

## API Changes

Existing Stage 12 endpoints are preserved. Responses are enriched with:
- `secretLastUpdatedAt`
- masked `secretReferenceId`
- `webhookVerificationMode`
- `lastHealthCheckStatus`
- `lastDiagnosticSummary`
- health-check `diagnostics`
- inbound-event `verificationStatus` and `verificationReason`
- sync-event `durationMs` and `errorCategory`

New safe secret metadata endpoints:
- `POST /api/v1/channels/connections/{id}/secret`
- `POST /api/v1/integrations/connections/{id}/secret`

These endpoints accept raw secret input but return only safe metadata.

## Security Notes

- Raw secrets are not returned in API responses.
- Raw secrets are not written to audit event metadata.
- Disabled/non-active channel connections reject webhooks.
- Invalid webhook verification rejects storage.
- Accepted webhooks normalize and store payloads only.
- Read-only syncs record event metadata and do not perform external writes.
- AI, chatbot, frontend, and connector adapters remain non-authoritative inputs.

## Local Verification

Backend targeted tests:

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

## Known Limitations

- Provider cryptographic verification is adapter-ready, not production-certified.
- `LocalDevelopmentSecretVaultService` is not a production vault.
- Demo ERP pilot stores sync payload summaries/counts only.
- External writes remain out of scope.
- Health checks avoid real provider network calls unless a future provider adapter explicitly supports safe read-only checks.

## Recommended Next Stage

Stage 14 should implement one production-grade read-only provider adapter with real vault integration, provider-specific signature verification, and a staging/mirror import model that still avoids trusted business-data mutation.
