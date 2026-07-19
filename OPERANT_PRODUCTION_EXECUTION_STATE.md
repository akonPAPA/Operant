document_version: 12
repository: akonPAPA/Operant
phase: 1
active_capability: P1-E bounded Control API and operantctl
next_capability: P1-F Connector Gateway protocol

# This file records DURABLE capability truth only. Transient Git/PR evidence (commit SHAs, dirty
# worktree state, staging instructions, exact-head CI results, owner-machine paths) belongs in the
# task report, the PR body, the GitHub review, and CI runs bound to an exact remote head — not here.

production_authentication:
  core_auth_mode: SIGNED_GATEWAY_HEADERS
  browser_auth_mode: BFF_OIDC_AUTHORIZATION_CODE
  runtime_implemented: true
  login_callback_pkce_state_nonce: IMPLEMENTED
  browser_login_binding: IMPLEMENTED
  configured_tenant_mapping: IMPLEMENTED
  production_session_store: REDIS_REQUIRED
  bff_to_core_authority: SERVER_RESOLVED_AND_SIGNED
  liveness_route: /api/bff/health
  readiness_route: /api/bff/ready

reconciliation_p1_a_d:
  p1_a_config: CODE_PROVEN (production guards, fail-closed config, placeholder rejection)
  p1_b_bff_boundary: CODE_PROVEN (static browser boundary, proxy boundary, transport isolation, redis credential isolation)
  p1_c_identity_planes: CODE_PROVEN (OIDC flow, session lifecycle, STAFF/tenant/service plane separation)
  p1_d_topology: CODE_PROVEN (exact network membership, negative mutations, lifecycle fault injection, real compose validate)
  p1_d_merged_pr: "#282"

p1e_bounded_control_read_foundation:
  implementation_status: CODE_PROVEN by the test contract below; production runtime NOT_PROVEN
  supported_commands:
    server: GET/HEAD /api/v1/internal/control/{status,health,readiness,diagnostics}
    client: version, config validate, credential import, status, health, readiness, diagnose
  absent_commands: logs, backup, restore, upgrade, rollback
  authority_boundaries:
    permissions: STAFF_CONTROL_READ for status/health/readiness; STAFF_CONTROL_DIAGNOSE for diagnostics
    route_authority_source: ApiRouteSecurityPolicy is the single source of route-to-permission truth; there is no duplicate method-authority filter
    write_shaped_and_unknown: POST/PUT/PATCH/DELETE/TRACE, unknown sub-paths, and trailing-slash variants fail closed and never invoke the control service
    options: CORS/preflight only; grants no control authority and no reusable authentication state
    credential_protocol: OPERANT_CONTROL_V1 binds method, path, raw query, content type, body SHA256, audience, credential alias, timestamp, and nonce
    registry: DISABLED is inactive with blank authority fields; ENABLED requires explicit alias, 64-hex random control secret, fixed audience, valid-from, finite future expiry, non-revoked state, allowlisted STAFF_CONTROL_* permissions, key version, and gateway/control key separation
    key_handling: control credential material is registry-owned; no accessor returns the internal array; HMAC verification uses a defensive copy that is zeroed after use; the test fingerprint is a non-reversible SHA-256 and never the raw key
    replay: shared gateway replay admission store with a separate control-plane/credential namespace
    ingress: the retired X-OrderPilot-Gateway-Key control selector fails closed and is stripped at the public proxy boundary
    plane_separation: tenant users (including tenant admins), external customers, and ordinary service accounts can never receive STAFF_CONTROL_*; a dedicated machine control credential performs only the fixed read operations in this foundation
  runtime_bounds:
    cli_total_deadline: one configured total operation budget bounds connect + TLS + response headers + complete bounded body read; the JDK exchange completes only when the bounded body completes, so the deadline covers the whole exchange; timeout maps to a redacted transport error
    cli_response_size_cap: MAX_RESPONSE_BYTES enforced by a custom bounded BodySubscriber while the body is consumed; the subscription is cancelled on overflow, deadline, or stream error; no unbounded read into memory
    diagnostics_aggregate_deadline: one monotonic absolute deadline per request bounds all sub-probes; database and Redis probes run concurrently and share the remaining budget; migration lookup runs only when the database is UP and budget remains; a result completing after the deadline never replaces the safe fallback and a timeout never reports a false-healthy dependency
    probe_bulkhead: a bounded number of live control dependency probes (maximum 4) via a non-blocking semaphore; saturation fails closed by mapping the dependency to DOWN and emitting a bounded saturation signal, never READY; the probe executor is shut down on destroy
    database_native_timeout: spring.datasource.hikari.connection-timeout is explicit and validated in production-like profiles to be <= the aggregate control-probe deadline
    redis_native_timeouts: Lettuce connect and command timeouts are explicit and validated in production-like profiles; timeout errors never leak host, port, credentials, or raw driver text
  client:
    module: apps/operantctl (Java 21, dependency-light JDK HTTP client + Jackson)
    credential_store: Windows current-user DPAPI, owner-only ACL, versioned blob, strict alias encoding, symlink/reparse rejection, bounded file size, atomic replacement, no plaintext fallback; non-Windows production store is deliberately unsupported
    tls: production requires HTTPS; localhost HTTP only in explicit local mode; no insecure toggle; optional PKCS12 trust store overrides the default JVM trust for the client
  tests_that_define_the_contract:
    - com.orderpilot.application.services.control.ControlPlaneStatusServiceTest
    - com.orderpilot.api.rest.InternalControlControllerSecurityTest
    - com.orderpilot.security.ControlPlaneKeySeparationSecurityTest
    - com.orderpilot.security.ControlPlaneSignedReadOnlyCredentialTest
    - com.orderpilot.security.ApiInternalRouteDefaultDenyTest
    - com.orderpilot.security.ApiRouteSecurityClassificationTest
    - com.orderpilot.security.production.ProductionConfigurationValidatorTest
    - com.operant.ctl.ControlApiClientResponseBoundTest
    - com.operant.ctl.OperantCtlCommandTest
  ci_gate:
    operantctl_job: mvn clean verify (shaded JAR builds and all module tests run), then java -jar the shaded artifact for version, then a packaged behavioural smoke that asserts a single executable JAR, correct Main-Class, no test/source/fixture entries, fail-closed exit codes, a per-invocation time bound, and secret absence
  production_runtime_not_proven:
    production_credential_issuance: NOT_PROVEN
    private_management_ingress: NOT_PROVEN
    live_deployed_operantctl: NOT_PROVEN
    human_staff_sso_mfa_jit_ticket_binding: NOT_PROVEN
    persistent_control_access_audit: NOT_PROVEN
    clean_host_runtime: NOT_PROVEN
  lifecycle_commands: NOT_IMPLEMENTED
  remaining_p1e_scope:
    - lifecycle operations slice (logs, backup, restore, upgrade, rollback) with state machine, idempotency, concurrency control, audit, redaction, fixed executor contracts, and P1-H runtime recovery proof

not_proven:
  - real identity-provider interoperability
  - deployed Redis failover and expiry behavior
  - DNS-pinned outbound identity-provider connections
  - clean-host Linux deployment and reboot lifecycle (scheduled for P1-H drills)
  - public Core ingress closure on a real host
  - independent external-customer authentication flow
  - independent service-account authentication flow
  - independent Operant support and maintenance authentication flow

open_p1:
  - P1-E lifecycle operations slice
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
