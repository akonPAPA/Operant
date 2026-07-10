# Data Authority Matrix

**Anchor commit (base):** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72`

| Field class | Resolved by | Client may send | P1-A enforcement |
| --- | --- | --- | --- |
| Tenant scope | `TenantContextFilter` / future session | Business filters only; not tenant authority | Demo RFQ endpoint disabled in production-like profiles at runtime + config |
| Actor / audit actor | `RequestActorResolver` (+ signing) | Never in body | Production requires non-placeholder `orderpilot.security.actor-signing-secret` |
| Permissions | Gateway HMAC or future session | Never as unsigned headers in prod | Production requires signed gateway header auth |
| Status / risk / margin / stock | Backend services | Never | Unchanged |
| Staff scope | `StaffIdentityResolver` + support grant | Never | Unchanged in P1-A |
| Connector / ERP write | ChangeRequest + policy | Never direct | `DEMO_ONLY` connector model unchanged |

Public response DTO leak rules unchanged (OP-CAP-31 contract law).
