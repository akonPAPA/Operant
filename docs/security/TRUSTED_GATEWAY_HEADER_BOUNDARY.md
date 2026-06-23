# Trusted Gateway Header Boundary (OP-CAP-43C / 43D)

OrderPilot Core can authenticate API requests from a **trusted edge gateway** using signed authority
headers instead of a session cookie. This document defines the deployment contract that makes that
model safe. It complements the app-level proofs:

- OP-CAP-43A/43B — MVC + non-MVC route classification and default-deny.
- OP-CAP-43C — the HMAC verifier (`GatewayHeaderSignatureVerifier` +
  `ApiHeaderAuthenticationFilter`) fails closed: missing / invalid / expired / future signatures,
  permission tampering, and a valid signature without the required permission are all rejected.
- OP-CAP-43D — a startup guard (`GatewayHeaderAuthProductionGuard`) prevents a production-like
  deployment from silently running in the dev/test (unsigned / no-secret) header-trust mode.

## Authority headers

The backend treats these headers as **authority** only when gateway-header auth is enabled and the
HMAC signature verifies:

- `X-Tenant-Id`
- `X-OrderPilot-Actor-Id`
- `X-OrderPilot-Permissions`
- `X-OrderPilot-Gateway-Timestamp`
- `X-OrderPilot-Gateway-Signature`
- any other `X-OrderPilot-*` authority header

## Rule 1 — clients must never supply authority headers directly

A request originating from a browser, Postman, curl, CLI, bot, connector, or AI worker must never be
allowed to set the authority headers above and have them trusted. "Hidden in the UI" is not security.
The only trusted producer of these headers is the edge gateway.

## Rule 2 — the edge gateway must strip inbound client copies

The edge gateway / reverse proxy (nginx, traefik, cloudflare, load balancer, API gateway) **must
strip every inbound client-supplied copy** of the authority headers before processing, so a client
cannot smuggle a pre-set value through:

```
X-Tenant-Id
X-OrderPilot-Actor-Id
X-OrderPilot-Permissions
X-OrderPilot-Gateway-Timestamp
X-OrderPilot-Gateway-Signature
X-OrderPilot-*   (all)
```

This stripping happens at the trust boundary and cannot be enforced by the backend alone — the
backend can only verify what it receives. Header stripping is a gateway/proxy responsibility and is
documented here because the proxy configuration lives outside this repository.

## Rule 3 — the gateway derives trusted context, then re-signs

After stripping, the gateway must:

1. **Derive** tenant / actor / permissions from a trusted source — authenticated identity, session,
   or API key context — never from the raw request body or client headers.
2. **Re-add** fresh authority headers with a current `X-OrderPilot-Gateway-Timestamp` and an
   `X-OrderPilot-Gateway-Signature` computed as an HMAC over the canonical string
   `METHOD\nURI\ntenantId\nactorId\npermissions\ntimestamp` using the shared secret.

## Rule 4 — the backend verifies the signed context

`ApiHeaderAuthenticationFilter` only authenticates when the signature verifies over method, URI,
tenant, actor, permissions, and a fresh timestamp (within the configured clock skew). A valid
signature authenticates context but **does not bypass** the permission policy — the
`ApiPermissionInterceptor` still enforces the route's required permission (OP-CAP-43C, test 6).

## Rule 5 — production configuration contract (enforced at startup)

In a production-like profile (`prod`, `production`, `cloud`, `staging`) the application **fails to
start** (`GatewayHeaderAuthProductionGuard`) when gateway-header auth is enabled but unsafe:

| Profile          | `enabled` | `signature-required` | `shared-secret` | Result               |
|------------------|-----------|----------------------|-----------------|----------------------|
| prod-like        | true      | false                | any             | **startup fails**    |
| prod-like        | true      | true                 | blank/missing   | **startup fails**    |
| prod-like        | true      | true                 | set             | starts               |
| prod-like        | false     | any                  | any             | starts (no trust)    |
| dev/test/default | true      | false                | any             | starts (dev-only)    |

- `signature-required=false` is **forbidden in production** — it is a dev/test convenience that
  trusts client headers without a signature.
- A **blank `shared-secret` is forbidden in production** when header trust is enabled.

## Rule 6 — secret handling

- The shared secret must be supplied via `ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET` from a secret
  manager / environment injection — **never committed to the repository**.
- The secret value is never logged and never included in startup-failure messages or API error
  responses (only the property name appears).

## Known limitation — replay window

Current replay protection is **timestamp + clock-skew only** (`clock-skew-seconds`, default 300). A
captured, still-fresh signed request could be replayed within the skew window. Nonce / `jti`
single-use admission is **not implemented** in this stage and is tracked as a future hardening item
(OP-CAP-43E / 44C — Gateway Header Replay Protection / Nonce-JTI Admission Guard).
