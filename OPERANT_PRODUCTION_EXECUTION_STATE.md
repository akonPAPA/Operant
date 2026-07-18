document_version: 10
updated_at: 2026-07-18T14:36:00Z
repository: akonPAPA/Operant
phase: 1
branch: feature/p1e-bounded-control-api
current_main_sha: b08f64163c156e1b8158301aa378d06b0fb57492
last_merged_pr: "#282"
last_closed_capability: P1-D (initial topology #281 + corrective root causes #282)
active_capability: P1-E bounded Control API and operantctl
active_branch: feature/p1e-bounded-control-api
active_start_sha: b08f64163c156e1b8158301aa378d06b0fb57492
next_capability: P1-F Connector Gateway protocol
p1d_closure:
  merged_pr: "#282"
  merge_commit: b08f64163c156e1b8158301aa378d06b0fb57492
  reviewed_head: e9aa39e685b0c2ea268b378d1deaf847f970d7ff
  tree_identity: a32e5274e0971ec15646371438dae85ff6133cf5 (reviewed head tree == merged main tree, byte-identical)
  exact_main_verification_at_b08f641:
    git_diff_head_check: PASS
    frontend_full_suite: 745/745 PASS
    p1d_topology_suite: 34/34 PASS
    typecheck: PASS
    lint: PASS
    backend_security_package: 525/525 PASS
    real_compose_validate: PASS (Docker 29.6.1 / Compose v5.3.0, synthetic env, exit 0)
  ci_at_reviewed_head: ALL PASS (backend, integration, frontend, build, compose config, release docker guard, CodeQL x3, Semgrep, Snyk, evidence gate)
production_authentication:
  core_auth_mode: SIGNED_GATEWAY_HEADERS
  browser_auth_mode: BFF_OIDC_AUTHORIZATION_CODE
  p1_c_code_status: MERGED_EXACT_HEAD_CI_PASS
  runtime_implemented: true
  login_callback_pkce_state_nonce: IMPLEMENTED
  browser_login_binding: IMPLEMENTED
  configured_tenant_mapping: IMPLEMENTED
  production_session_store: REDIS_REQUIRED
  bff_to_core_authority: SERVER_RESOLVED_AND_SIGNED
  liveness_route: /api/bff/health
  readiness_route: /api/bff/ready
reconciliation_p1_a_d:
  p1_a_config: CODE_PROVEN at a32e5274 (production guards, fail-closed config, placeholder rejection)
  p1_b_bff_boundary: CODE_PROVEN at a32e5274 (static browser boundary, proxy boundary, transport isolation, redis credential isolation)
  p1_c_identity_planes: CODE_PROVEN at a32e5274 (OIDC flow, session lifecycle, STAFF/tenant/service plane separation)
  p1_d_topology: CODE_PROVEN at a32e5274 (exact network membership, negative mutations, lifecycle fault injection, real compose validate)
not_proven:
  - real identity-provider interoperability
  - deployed Redis failover and expiry behavior
  - DNS-pinned outbound identity-provider connections
  - clean-host Linux deployment and reboot lifecycle (scheduled for P1-H drills)
  - public Core ingress closure on a real host
  - independent external-customer authentication flow
  - independent service-account authentication flow
  - independent Operant support and maintenance authentication flow
p1e_current_working_tree:
  decision: P1-E PARTIAL
  scope: bounded control read API, dedicated control credential protocol, Windows DPAPI-backed operantctl read client
  gates_0_to_5: CORRECTIVE_LOCAL_PARTIAL_PROOF
  gate_6_lifecycle_commands: NOT_IMPLEMENTED
  server:
    routes: GET/HEAD /api/v1/internal/control/{status,health,readiness,diagnostics}; OPTIONS advertises GET,HEAD,OPTIONS; write-shaped methods and unknown paths remain denied or method-not-allowed after authorization
    permissions: STAFF_CONTROL_READ and STAFF_CONTROL_DIAGNOSE, resolved server-side from a control credential registry
    credential_protocol: OPERANT_CONTROL_V1 binds method, path, raw query, content type, body SHA256, audience, credential alias, timestamp, and nonce
    registry: DISABLED remains inactive with blank authority fields; ENABLED requires explicit alias, 64-hex random control secret, fixed audience, valid-from, finite future expiry, non-revoked state, allowlisted STAFF_CONTROL_* permissions, key version, and gateway/control key separation
    replay: existing shared gateway replay admission store with separate control-plane/credential namespace
    ingress: retired X-OrderPilot-Gateway-Key control selector fails closed and is stripped at the public proxy boundary
  client:
    module: apps/operantctl (Java 21)
    commands: version, config validate, status, health, readiness, diagnose
    absent_commands: logs, backup, restore, upgrade, rollback
    credential_store: Windows current-user DPAPI, owner-only ACL, versioned blob, strict alias encoding, symlink/reparse rejection, bounded file size, atomic replacement, no plaintext fallback
    tls: production requires HTTPS, localhost HTTP only in explicit local mode, no insecure toggle, optional PKCS12 trust store overrides default JVM trust for the client
  dependency_decision:
    jna_platform: 5.19.1
    transitive_dependencies: net.java.dev.jna:jna 5.19.1
    jackson: 2.22.1 (databind/core resolved by local dependency tree; GitHub Snyk exact-head result NOT_PROVEN)
  corrective_local_verification:
    starting_head: ff40c637a8c817ec016a772a7e81950f016504d6
    corrective_commit: NOT_CREATED_BY_CODEX_HIGHER_PRIORITY_GIT_INSTRUCTION
    core_control_credential_tests: ControlPlaneKeySeparationSecurityTest + ProductionConfigurationValidatorTest = 32/32 PASS
    core_route_status_security_tests: InternalControlControllerSecurityTest + ApiInternalRouteDefaultDenyTest + ApiRouteSecurityClassificationTest + GatewayHeaderAuthProductionGuardTest + ControlPlaneStatusServiceTest = 367/367 PASS
    operantctl_targeted_mvn_test: ControlApiClientResponseBoundTest + OperantCtlCommandTest + ControlApiClientTlsTest + CtlConfigTest = 36/36 PASS
    operantctl_full_mvn_test: 38/38 PASS
    operantctl_mvn_package: PASS target/operantctl-0.1.0-SNAPSHOT.jar
    operantctl_dependency_tree: jackson-databind 2.22.1, jackson-core 2.22.1, jackson-annotations 2.22
    local_snyk: NOT_PROVEN (blocked by external data-exfiltration policy for snyk test)
    github_snyk_exact_head: NOT_PROVEN
    p1d_topology_validator: PASS (node scripts/validate-p1d-production-topology.mjs)
    core_full_mvn_test: PASS exit 0; Surefire XML totals 2698 tests, 0 failures, 0 errors, 45 skipped
    frontend_full_verification: npm test 745/745 PASS; npm run lint PASS; npm run typecheck PASS; npm run build PASS
  corrective_not_proven:
    production_management_ingress: NOT_PROVEN
    clean_host_runtime: NOT_PROVEN
    lifecycle_commands: NOT_IMPLEMENTED
    connection_acquisition_total_readiness_deadline: NOT_PROVEN
    exact_head_ci: NOT_PROVEN
    merge: NOT_PERFORMED
open_p1:
  - P1-E lifecycle operations slice: logs, backup, restore, upgrade, rollback with state machine, idempotency, concurrency control, audit, redaction, fixed executor contracts, and P1-H runtime recovery proof
  - P1-F/P1-G real agent registry and agent status source
  - real AI/provider registry and provider status source
  - final operantctl wiring after real lifecycle, agent, and provider sources exist
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
next_bounded_action: Owner reviews the local corrective patch, creates the corrective commit if accepted, runs exact-head verification/CI/PR review, and makes the merge decision. GitHub Snyk, private production management ingress, clean-host runtime, and lifecycle commands remain NOT_PROVEN/NOT_IMPLEMENTED.