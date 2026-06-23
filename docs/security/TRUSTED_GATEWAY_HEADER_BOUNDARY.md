# Trusted Gateway Header Boundary (OP-CAP-43C / 43D / 43E / 43F)

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

## Authority headers

The backend treats these headers as authority only when gateway-header auth is enabled and the HMAC
signature verifies:

- `X-Tenant-Id`
- `X-OrderPilot-Actor-Id`
- `X-OrderPilot-Permissions`
- `X-OrderPilot-Gateway-Timestamp`
- `X-OrderPilot-Gateway-Nonce`
- `X-OrderPilot-Gateway-Signature`
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
X-OrderPilot-Actor-Id
X-OrderPilot-Permissions
X-OrderPilot-Gateway-Timestamp
X-OrderPilot-Gateway-Nonce
X-OrderPilot-Gateway-Signature
X-OrderPilot-*   (all)
```

This stripping happens at the trust boundary and cannot be enforced by the backend alone. Header
stripping is a gateway/proxy responsibility because the proxy configuration lives outside this
repository.

## Rule 3 - the gateway derives trusted context, then re-signs

After stripping, the gateway must:

1. Derive tenant, actor, and permissions from a trusted source: authenticated identity, session, or
   API key context. Never derive authority from the raw request body or client headers.
2. Generate a unique nonce per signed request (`X-OrderPilot-Gateway-Nonce`, for example UUID/jti).
3. Re-add fresh authority headers with a current `X-OrderPilot-Gateway-Timestamp`,
   `X-OrderPilot-Gateway-Nonce`, and `X-OrderPilot-Gateway-Signature` computed as an HMAC over:

```text
METHOD\nURI\ntenantId\nactorId\npermissions\ntimestamp\nnonce
```

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
