# Trusted Gateway Header Boundary (OP-CAP-43C / 43D / 43E / 43F / 43G)

OrderPilot Core can authenticate API requests from a trusted edge gateway using signed authority
headers instead of a session cookie. This document defines the deployment contract that makes that
model safe. It complements the app-level proofs:

- OP-CAP-43A/43B: MVC and non-MVC route classification and default-deny.
- OP-CAP-43C: the HMAC verifier (`GatewayHeaderSignatureVerifier` +
  `ApiHeaderAuthenticationFilter`) fails closed for missing, invalid, expired, future, and tampered
  signed authority headers.
- OP-CAP-43D: `GatewayHeaderAuthProductionGuard` prevents production-like deployments from running
  in unsigned/no-secret gateway-header mode.
- OP-CAP-43E/43F: gateway nonces are required in signed mode, included in the HMAC canonical string,
  and admitted once through a memory or Redis replay store.
- OP-CAP-43G: `gateway-header-strip-nginx-example.conf` is a concrete deployment-review artifact for
  the trusted gateway boundary. It proves the intended strip/re-inject topology without adding a new
  production gateway service in this repository.

## Threat model

The trusted-header model must defend against:

- Client header spoofing: a browser, curl, Postman, bot, connector, or AI worker sends
  `X-Tenant-Id`, `X-OrderPilot-Actor-Id`, or `X-OrderPilot-Permissions` directly.
- Header smuggling through a proxy/load balancer that forwards public `X-OrderPilot-*` headers to
  core-api.
- Reuse of a captured signed request inside the timestamp skew window.
- Direct public exposure of core-api, bypassing the trusted gateway that strips and signs headers.

The deployment boundary is therefore part of the security model. Backend verification is necessary
but not enough if public traffic can reach core-api directly.

## Authority headers

The backend treats these headers as authority only when gateway-header auth is enabled and the HMAC
signature verifies:

- `X-Tenant-Id`
- `X-OrderPilot-Tenant-Id` (strip legacy/alias form if any proxy or client uses it)
- `X-OrderPilot-Actor-Id`
- `X-OrderPilot-Permissions`
- `X-OrderPilot-Gateway-Timestamp`
- `X-OrderPilot-Gateway-Nonce`
- `X-OrderPilot-Gateway-Signature`
- `X-OrderPilot-Actor-Signature`
- `X-OrderPilot-Actor-Timestamp`
- any other `X-OrderPilot-*` authority header

## Rule 1 - clients must never supply authority headers directly

A request originating from a browser, Postman, curl, CLI, bot, connector, or AI worker must never be
allowed to set the authority headers above and have them trusted. "Hidden in the UI" is not security.
The only trusted producer of these headers is the edge gateway.

## Rule 2 - the edge gateway must strip inbound client copies

The edge gateway or reverse proxy must strip every inbound client-supplied copy of the authority
headers before processing, so a client cannot smuggle a pre-set value through:

```text
X-Tenant-Id
X-OrderPilot-Tenant-Id
X-OrderPilot-Actor-Id
X-OrderPilot-Permissions
X-OrderPilot-Gateway-Timestamp
X-OrderPilot-Gateway-Nonce
X-OrderPilot-Signature-Version
X-OrderPilot-Content-SHA256
X-OrderPilot-Gateway-Signature
X-OrderPilot-Actor-Signature
X-OrderPilot-Actor-Timestamp
X-OrderPilot-*   (all)
```

This stripping happens at the trust boundary and cannot be enforced by the backend alone. Header
stripping is a gateway/proxy responsibility because the proxy configuration lives outside this
repository.

The review artifact `docs/security/gateway-header-strip-nginx-example.conf` demonstrates this
header-stripping/private-ingress boundary by using `proxy_pass_request_headers off` for the backend
hop and explicitly re-adding only gateway-owned headers after authentication/signing. It is not a
complete HMAC v2 signer for body-bearing mutations: its `auth_request` subrequest does not receive
the request body, so it cannot independently compute SHA-256 over the exact forwarded body bytes.
Body-bearing requests must be signed by the existing body-aware BFF/gateway path, or by a future
trusted gateway/auth component that reads, hashes, signs, and forwards the exact same bytes. For the
auth/signing subrequest, the artifact clears all currently known OrderPilot authority headers so
spoofed public values cannot be reused. NGINX OSS cannot wildcard-clear arbitrary future header names;
the auth/signing service must also ignore new `X-OrderPilot-*` authority/debug headers by policy.

## Rule 3 - the gateway derives trusted context, then re-signs

After stripping, the gateway must:

1. Derive tenant, actor, and permissions from a trusted source: authenticated identity, session, or
   API key context. Never derive authority from the raw request body or client headers.
2. Generate a unique nonce per signed request (`X-OrderPilot-Gateway-Nonce`, for example UUID/jti).
3. Re-add fresh authority headers with a current `X-OrderPilot-Gateway-Timestamp`,
   `X-OrderPilot-Gateway-Nonce`, `X-OrderPilot-Signature-Version` (`2`),
   `X-OrderPilot-Content-SHA256`, and `X-OrderPilot-Gateway-Signature` computed as HMAC-SHA-256 over
   the v2 canonical string (LF-separated):

```text
ORDERPILOT_GATEWAY_V2
METHOD
PATH
RAW_QUERY
CONTENT_TYPE
BODY_SHA256_HEX
tenantId
actorId
permissions
timestamp
nonce
```

The shared secret must be exactly 64 hexadecimal characters (`openssl rand -hex 32`). HMAC uses the
decoded 32 raw bytes, never the ASCII hex text. There is no production v1 fallback.

If the proxy layer cannot compute HMAC safely over the exact forwarded request facts and body bytes,
it must not fake this step. A trusted gateway/auth service must perform signing and return only
derived tenant, actor, permissions, timestamp, nonce, content hash, and signature values to the proxy.
The sample NGINX artifact uses `auth_request` variables only as a header-strip/private-ingress
reference and intentionally contains no signing secret; with `proxy_pass_request_body off`, it is
complete only for empty-body/read requests unless paired with a body-aware signer in the request path.

## Trusted signer contract (OP-CAP-43H)

This repository still does not implement OIDC, SSO, or a production gateway/auth service. OP-CAP-43H
defines the local contract a future trusted signer must satisfy and pins it with backend compatibility
tests.

Signer input source of truth:

- Tenant id, actor id, and permissions must be resolved from authenticated identity, session, or API
  key context at the trusted gateway/auth boundary.
- Tenant id, actor id, permissions, role, status, approval, execution, risk, stock, margin, source,
  timestamp, nonce, and signature authority must not come from public client headers, request bodies,
  AI workers, bots, or connectors.
- The signer must use the request facts required by the backend verifier: uppercase HTTP method,
  backend path, raw query string without a leading `?`, normalized content type, and the SHA-256 hash
  of the exact forwarded body bytes.
- The signer must generate fresh signing facts: current timestamp and a unique nonce/JTI for every
  signed backend request.

Signer output headers use the existing backend verifier names:

```text
X-Tenant-Id
X-OrderPilot-Actor-Id
X-OrderPilot-Permissions
X-OrderPilot-Gateway-Timestamp
X-OrderPilot-Gateway-Nonce
X-OrderPilot-Signature-Version
X-OrderPilot-Content-SHA256
X-OrderPilot-Gateway-Signature
```

Canonical string (LF-separated):

```text
ORDERPILOT_GATEWAY_V2
METHOD
PATH
RAW_QUERY
CONTENT_TYPE
BODY_SHA256_HEX
tenantId
actorId
permissions
timestamp
nonce
```

`METHOD` is upper-cased by the backend verifier. `PATH` is the backend servlet request path used by
`HttpServletRequest#getRequestURI()`. `RAW_QUERY` is the backend raw query string from
`HttpServletRequest#getQueryString()` without a leading `?`, or an empty string when absent.
`CONTENT_TYPE` is the verifier-normalized content type, or an empty string for an empty body.
`BODY_SHA256_HEX` is the lowercase SHA-256 of the exact forwarded bytes; the empty-body value is
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`. Permissions are serialized as
the single server-derived route permission used for the backend request. Fields are UTF-8 text joined
by exactly one LF; CR/LF inside any field is rejected. There is no production legacy/v1 downgrade.

Timestamp, nonce, and replay contract:

- `X-OrderPilot-Gateway-Timestamp` must be within the backend clock-skew window.
- `X-OrderPilot-Gateway-Nonce` must be unique per signed backend request and bound into the HMAC.
- The backend admits each `(tenant, actor, nonce)` once through `GatewayHeaderReplayAdmissionStore`.
- Multi-instance production must use `orderpilot.security.gateway-header-auth.replay-store=redis` or
  an equivalent owner-approved distributed replay admission mechanism.
- Gateway signing secrets must be stored in a secret manager or equivalent secret-injection system,
  never in committed config files, docs, tests, logs, or examples.

Deployment dependency:

- The OP-CAP-43G header-strip boundary remains required. Public copies of all authority headers must
  be stripped before the signer runs, and core-api must remain private-only behind the trusted gateway.
- The signer injects fresh signed headers only after authentication and authority resolution.
- Core-api verifies signature version, content SHA-256, signature, timestamp, nonce, and replay admission before trusting gateway
  authority.

## Required production topology

Production multi-instance mode:

```text
public client
-> trusted gateway/proxy strips public authority headers
-> trusted gateway/auth service resolves tenant/actor/permissions and signs fresh headers
-> private core-api upstream verifies HMAC, timestamp, nonce, and Redis replay admission
-> backend services perform authorized mutations and audit
```

Core-api must not be reachable directly from the public internet. It should listen only on a private
network, service mesh, or internal load balancer reachable from the trusted gateway. If a public
client can bypass the gateway and reach core-api, OP-CAP-43G is not deployed.

## Rule 4 - the backend verifies the signed context and admits each nonce once

`ApiHeaderAuthenticationFilter` only authenticates when the signature verifies over method, URI,
tenant, actor, permissions, a fresh timestamp within the configured clock skew, and the nonce. A
valid signature authenticates context but does not bypass the permission policy:
`ApiPermissionInterceptor` still enforces the route's required permission.

### Rule 4a - replay protection

Timestamp freshness alone does not prove single use. A captured, still-fresh signed request could be
replayed inside the skew window. The backend therefore enforces first-use nonce admission through
`GatewayHeaderReplayAdmissionStore`:

- The nonce is required when `signature-required=true`; a valid signature with no nonce is rejected
  with `401 AUTHENTICATION_REQUIRED`.
- The nonce is bound into the HMAC canonical string, so changing it without re-signing fails
  verification and does not consume a nonce slot.
- Each `(tenant, actor, nonce)` is admitted at most once within the replay window. A duplicate is
  rejected with `401`.
- Admission happens only after signature and freshness checks pass.
- The replay window is twice the clock-skew window, so an entry outlives the full freshness window of
  the request that created it.
- Replay-store keys are SHA-256 digest keys, for example `op:gw-replay:{digest}`. The store must not
  persist raw tenant ids, actor ids, nonces, signatures, canonical strings, or shared secrets.

`orderpilot.security.gateway-header-auth.replay-store=memory` uses `GatewayHeaderReplayGuard`, a
bounded in-memory store. This is single-instance only.

`orderpilot.security.gateway-header-auth.replay-store=redis` uses
`RedisGatewayHeaderReplayAdmissionStore`, backed by Spring Data Redis `setIfAbsent(key, value, ttl)`
with Redis `SET NX EX` semantics. Duplicate nonces return false. Redis unavailability or Redis
exceptions fail closed by returning false, so signed headers are not authenticated and clients receive
the normal authentication failure without Redis/key/signature details.

## Rule 5 - production configuration contract

In a production-like profile (`prod`, `production`, `cloud`, `staging`) the application fails to
start (`GatewayHeaderAuthProductionGuard`) when gateway-header auth is enabled in an unsafe shape:

| Profile          | `enabled` | `signature-required` | `shared-secret` | `replay-store` | Result            |
|------------------|-----------|----------------------|-----------------|----------------|-------------------|
| prod-like        | true      | false                | any             | any            | startup fails     |
| prod-like        | true      | true                 | blank/missing   | any            | startup fails     |
| prod-like        | true      | true                 | set             | memory/default | startup fails     |
| prod-like        | true      | true                 | set             | redis          | starts            |
| prod-like        | false     | any                  | any             | any            | starts (no trust) |
| dev/test/default | true      | false                | any             | memory/default | starts (dev only) |

- `signature-required=false` is forbidden in production.
- A blank `shared-secret` is forbidden in production when header trust is enabled.
- Memory replay admission is forbidden in production signed mode by default because it is
  single-instance only.
- A deliberately single-instance production deployment may opt in with
  `orderpilot.security.gateway-header-auth.allow-single-instance-replay-store-in-production=true`.
  Multi-instance deployments must use `replay-store=redis` or equivalent gateway-level replay
  prevention.

## Deployment review checklist

- Public listener terminates only at the trusted gateway/proxy, not core-api.
- Gateway/proxy strips or allowlists away `X-Tenant-Id`, `X-OrderPilot-Tenant-Id`, every
  `X-OrderPilot-Gateway-*` header, `X-OrderPilot-Actor-*`, `X-OrderPilot-Permissions`, and any other
  `X-OrderPilot-*` authority/debug header before backend forwarding.
- Gateway/auth service derives tenant, actor, and permissions from authenticated identity/session/API
  key context, never from public request headers or request body authority fields.
- Gateway/auth service generates a fresh timestamp and nonce per signed backend request.
- Gateway/auth service sets `X-OrderPilot-Signature-Version: 2`, computes
  `X-OrderPilot-Content-SHA256` over the exact forwarded bytes, and computes
  `X-OrderPilot-Gateway-Signature` over exactly:

```text
ORDERPILOT_GATEWAY_V2
METHOD
PATH
RAW_QUERY
CONTENT_TYPE
BODY_SHA256_HEX
tenantId
actorId
permissions
timestamp
nonce
```
- Core-api production-like profile has `gateway-header-auth.enabled=true`,
  `signature-required=true`, a non-blank shared secret, and `replay-store=redis` for multi-instance
  deployments.
- Redis replay admission is private to the deployment and not used to store raw tenant ids, actor ids,
  nonces, signatures, canonical strings, or shared secrets.
- Logs and config examples contain placeholders only, never real gateway signing secrets.

## Rule 6 - secret handling

- The shared secret must be supplied via `ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET` from a secret
  manager or environment injection, never committed to the repository.
- The secret value is never logged and never included in startup-failure messages or API error
  responses.
- Replay keys do not include the shared secret, signature, canonical string, tenant id, actor id, or
  nonce in raw form.

## Replay protection status

- Timestamp + clock-skew freshness: implemented (`clock-skew-seconds`, default 300).
- Single-use nonce/jti admission: implemented for one backend instance via `GatewayHeaderReplayGuard`.
- Distributed multi-instance replay protection: implemented via `RedisGatewayHeaderReplayAdmissionStore`
  when `orderpilot.security.gateway-header-auth.replay-store=redis`.
- Production-like signed mode now fails startup on memory/default replay admission unless the
  documented single-instance override is set.

## Remaining risks not proven by repository artifacts

- The sample NGINX artifact is not a live production deployment.
- The repository does not implement OIDC/SSO or a real gateway/auth signer service.
- Direct-backend network isolation must be enforced by deployed infrastructure, firewall rules,
  private networking, or service mesh policy outside this repository.
- Live Redis availability and live gateway HMAC signing are not proven by this document.
