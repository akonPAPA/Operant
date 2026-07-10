# Trust Boundary Matrix

**Anchor commit (base):** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72`

| Plane | Trusted ingress | Current @ base SHA | Target (Phase 1) | P1-A change |
| --- | --- | --- | --- | --- |
| Tenant operator browser | BFF session → Core | Client sends `X-Tenant-Id` / permissions to Core URL | BFF-only, no client authority headers | Startup rejects demo RFQ + unsigned gateway in `prod` profiles |
| Trusted gateway | HMAC-signed headers | Optional; dev unsigned mode | Required signed gateway in production-like profiles | `GatewayHeaderAuthProductionRules` requires enabled+signed+non-placeholder secret |
| Staff / support | Staff identity + grant | Resolver seam + tests | Production staff SSO (P1-C) | OIDC enable flag fails startup |
| Service / bot | Webhook + service auth | Webhook routes + tests | Unchanged in P1-A | — |
| Control API | operantctl credentials | Not implemented | Authenticated control plane (P1-E) | — |
| Connector agent | mTLS outbound | Not implemented | Connector Gateway (P1-F/G) | — |

**Client must never own:** tenant, actor, staff, permissions, approval, connector execution authority (see root `AGENTS.md`).

## Temporary production authentication decision (P1-A)

| Constant | Value |
| --- | --- |
| `CURRENT_PRODUCTION_AUTH_MODE` | `SIGNED_GATEWAY_HEADERS` |
| `OIDC_STATUS` | `NOT_IMPLEMENTED` |
| `OIDC_ENABLED_IN_PRODUCTION` | `FAIL_CLOSED` (`orderpilot.security.oidc.enabled=true` rejects startup) |

**P1-C** must replace the temporary OIDC rejection with real OIDC/session identity mapping and tenant membership resolution. Until then, production-like profiles require signed gateway header authentication plus non-placeholder actor and datasource secrets (`ProductionConfigurationValidator`).
