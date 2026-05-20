# Security Baseline

## Non-negotiable rule

AI, chatbot, frontend, and connectors must never directly write to trusted business data, master database, ERP, 1C, accounting system, warehouse system, customer master data, product master data, price rules, inventory, quotes, or orders.

## Required mutation path

All business mutations must go through:

1. typed backend command services;
2. authentication;
3. RBAC/ABAC authorization;
4. tenant isolation policy;
5. deterministic validation;
6. approval gate if risky;
7. transaction service;
8. audit event;
9. outbox event if external integration is needed.

## Stage 1 controls

- Tenant context placeholder exists in core-api.
- AuditEvent service exists and writes append-only audit records at application level.
- Frontend has no direct DB dependency.
- AI worker has no direct DB dependency and marks output advisory only.
- Windows connector is placeholder only and documented as outbound-only, scoped, and read-only by default.

## Future required controls

- Real authentication and session/JWT validation.
- RBAC and ABAC policy enforcement.
- Tenant isolation tests for every tenant-owned entity.
- Idempotency handling for mutation endpoints.
- Transactional outbox for integration events.
- ChangeRequest and approval model before external writes.
- File upload validation, malware scanning hook, and object storage quarantine.
- Secret management and audit monitoring.