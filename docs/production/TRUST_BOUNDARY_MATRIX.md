# Trust Boundary Matrix

**Anchor commit (base):** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72`

| Plane | Trusted ingress | Current @ base SHA | Target (Phase 1) | Current delta |
| --- | --- | --- | --- | --- |
| Tenant operator browser | BFF session -> Core | Client sends `X-Tenant-Id` / permissions to Core URL | BFF-only, no client authority headers | PR #267 @ `1210f94`: tenant `*-api` clients use `dashboardApiFetch` → same-origin `/api/bff` in production BFF mode; server RSC reads use request cookie-forwarded `/api/bff` (no client authority headers); in-process isolation proven in unit tests; BFF signs gateway authority from opaque server session; CSRF on mutations; internal/support routes denied on tenant BFF. EV-P1B-012..018. Not proven: P1-C identity, live Redis, deployed topology, public Core ingress closure. |
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

## Business logic and visibility boundary (PR #267 @ `1210f94`)

### Tenant user access

| Question | Answer |
| --- | --- |
| Who should see/call/use it? | Authenticated tenant operators via opaque HttpOnly `op_session`, route-matched BFF permissions, and server-signed gateway headers to Core. |
| Who must never see/call/use it? | Anonymous users; other tenants; external customers; service accounts; Operant staff via tenant sessions; browsers supplying tenant/actor/permission headers. |
| What can the client send? | Business intent, allowlisted path/query, public DTO fields, CSRF token (`op_csrf`), bounded idempotency keys, allowed conditional headers. |
| What must backend resolve? | Tenant, actor, permissions, session state, route permission, gateway signature, resource ownership, valid transitions. |
| Which permission protects it? | Per-route BFF registry permission + Core `ApiPermission` policy. |
| Unauthorized access tests | No/wrong session; wrong tenant; missing permission; wrong method; unregistered route; expired/revoked session; support route denial (`bff-proxy-boundary`, `bff-server-transport-isolation`, E2E `p1b-bff-boundary.spec.ts`). |
| Denied request must not mutate | Zero Core calls on CSRF/session/registry failures; zero Redis write on invalid config (TTL tests). |
| Valid flow tests | Browser read/mutation via `/api/bff`; valid CSRF exactly once; E2E tenant read; isolation tests for concurrent sessions. |
| Not proven | Live Redis; real OIDC membership; full RSC cookie-forwarding in production deployment; remote CI. |

### External customer access

Public order tracking uses `public-order-tracking-api` / public Core base URL only. Never receives tenant `op_session` or tenant BFF authority.

### Service account access

Webhooks, bots, connectors, and workers retain separate machine authority. No `op_session` or browser CSRF path.

### Operant support and maintenance access plane

`internal-support-operations-api` fails closed with `SUPPORT_PLANE_NOT_CONFIGURED` when production BFF is active. No fallback to tenant BFF or public Core. Average tenant users cannot reach staff/support routes through tenant BFF (registry + E2E).

