# Trust Boundary Matrix

**Anchor commit (base):** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72`

| Plane | Trusted ingress | Current @ base SHA | Target (Phase 1) | Current delta |
| --- | --- | --- | --- | --- |
| Tenant operator browser | BFF session -> Core | Client sends `X-Tenant-Id` / permissions to Core URL | BFF-only, no client authority headers | PR #267 local proof at af01e52: production browser transport resolves same-origin `/api/bff`, API requests without a session return JSON 401, page navigations redirect to login, BFF signs authority server-side from the session, and client authority headers are stripped. Maven security tests (285+470 pass) verify Core routes deny unregistered ingress. Not proven: P1-C real identity, live Redis, deployed topology, public Core ingress closure. |
| Trusted gateway | HMAC-signed headers | Optional; dev unsigned mode | Required signed gateway in production-like profiles | P1-A requires enabled+signed+non-placeholder secret; P1-B BFF uses the gateway signer before Core calls. |
| Staff / support | Staff identity + grant | Resolver seam + tests | Production staff SSO (P1-C) | OIDC enable flag still fails startup until P1-C implements real identity mapping. |
| Service / bot | Webhook + service auth | Webhook routes + tests | Preserve backend-owned source/actor authority | No P1-B change to production webhook mutation authority. |
| Control API | operantctl credentials | Not implemented | Authenticated control plane (P1-E) | Not in P1-B scope. |
| Connector agent | mTLS outbound | Not implemented | Connector Gateway (P1-F/G) | Not in P1-B scope. |

**Client must never own:** tenant, actor, staff, permissions, approval, connector execution authority (see root `AGENTS.md`).

## Temporary production authentication decision (P1-A)

| Constant | Value |
| --- | --- |
| `CURRENT_PRODUCTION_AUTH_MODE` | `SIGNED_GATEWAY_HEADERS` |
| `OIDC_STATUS` | `NOT_IMPLEMENTED` |
| `OIDC_ENABLED_IN_PRODUCTION` | `FAIL_CLOSED` (`orderpilot.security.oidc.enabled=true` rejects startup) |

**P1-C** must replace the temporary OIDC rejection with real OIDC/session identity mapping and tenant membership resolution. Until then, production-like profiles require signed gateway header authentication plus non-placeholder actor and datasource secrets (`ProductionConfigurationValidator`).
