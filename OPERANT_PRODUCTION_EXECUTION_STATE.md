document_version: 11
updated_at: 2026-07-18T20:45:00Z
repository: akonPAPA/Operant
phase: 1
branch: feature/p1e-bounded-control-api
committed_head: 8e82517a06bf824823b0e357c9c088caeca3e1f1
current_main_sha: b08f64163c156e1b8158301aa378d06b0fb57492
last_merged_pr: "#282"
last_closed_capability: P1-D (initial topology #281 + corrective root causes #282)
active_capability: P1-E bounded Control API and operantctl
active_branch: feature/p1e-bounded-control-api
active_start_sha: b08f64163c156e1b8158301aa378d06b0fb57492
next_capability: P1-F Connector Gateway protocol
pr_283_final_local_verification:
  local_verdict: READY_FOR_OWNER_CORRECTIVE_COMMIT
  remote: NOT_INSPECTED
  production_runtime: NOT_PROVEN
  dirty_working_tree_correction: present (unstaged corrective delta on HEAD 8e82517)
  corrective_files:
    - apps/core-api/src/main/java/com/orderpilot/application/services/control/ControlPlaneStatusService.java
    - apps/core-api/src/test/java/com/orderpilot/application/services/control/ControlPlaneStatusServiceTest.java
    - apps/core-api/src/test/java/com/orderpilot/security/TrustedGatewayHeaderStripArtifactTest.java
    - apps/operantctl/src/main/java/com/operant/ctl/OperantCtl.java
    - apps/operantctl/src/test/java/com/operant/ctl/OperantCtlCommandTest.java
    - docs/product/OPERANT_WORLD_CLASS_FRONTEND_MASTER_PLAN_V1.md (deletion)
    - docs/security/gateway-header-strip-nginx-example.conf
    - OPERANT_PRODUCTION_EXECUTION_STATE.md
  finding_h: PASS (aggregate dependency-probe deadline, Future cancel, interrupt preserve, @PreDestroy shutdownNow, bounded System.Logger; @Autowired on production constructor to keep Spring context wiring)
  owner_document_preservation:
    path: C:\OrderPilot\owner-artifacts\OPERANT_WORLD_CLASS_FRONTEND_MASTER_PLAN_V1.md
    sha256: 0F4322B3AE39245154820E60740189D062E50F727D4CEC77EC52A614ECDDC2EB
  operantctl:
    clean_verify: PASS (43/43)
    version_smoke: PASS
    jar_sha256: BB0E508913252C6FD00890E2A058991196A23F4761B15DB7EA156483E07BDFAC
    jar_bytes: 5784836
    packaged_behavioural_smokes: PASS (8/8 cases; secret leak false)
  core:
    targeted_p1e: PASS (394/394)
    full_suite: PASS (2700 tests, 0 failures, 0 errors, 45 skipped)
  frontend:
    topology_validate: PASS
    topology_tests: PASS (41/41)
    npm_test: PASS (752/752)
    lint: PASS
    typecheck: PASS
    build: PASS
  exact_next_owner_action: Stage the corrective working-tree set (including this execution-state update and the frontend-plan deletion), create the owner corrective commit locally, then run exact-head CI/PR review. Do not claim remote green or production proven until corresponding evidence exists.
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
  decision: P1-E LOCAL_CORRECTIVE_READY_FOR_OWNER_COMMIT
  scope: bounded control read API, dedicated control credential protocol, Windows DPAPI-backed operantctl read client
  gates_0_to_5: CORRECTIVE_LOCAL_PROOF_COMPLETE
  gate_6_lifecycle_commands: NOT_IMPLEMENTED
  finding_h_aggregate_dependency_deadline: PASS
  server:
    routes: GET/HEAD /api/v1/internal/control/{status,health,readiness,diagnostics}; OPTIONS advertises GET,HEAD,OPTIONS; write-shaped methods and unknown paths remain denied or method-not-allowed after authorization
    permissions: STAFF_CONTROL_READ and STAFF_CONTROL_DIAGNOSE, resolved server-side from a control credential registry
    credential_protocol: OPERANT_CONTROL_V1 binds method, path, raw query, content type, body SHA256, audience, credential alias, timestamp, and nonce
    registry: DISABLED remains inactive with blank authority fields; ENABLED requires explicit alias, 64-hex random control secret, fixed audience, valid-from, finite future expiry, non-revoked state, allowlisted STAFF_CONTROL_* permissions, key version, and gateway/control key separation
    replay: existing shared gateway replay admission store with separate control-plane/credential namespace
    ingress: retired X-OrderPilot-Gateway-Key control selector fails closed and is stripped at the public proxy boundary
    readiness_deadline: aggregate dependency-probe deadline bounds DB/Redis acquisition; timeout returns DOWN and never READY; futures cancelled; probe executor shut down on destroy
  client:
    module: apps/operantctl (Java 21)
    commands: version, config validate, credential import, status, health, readiness, diagnose
    absent_commands: logs, backup, restore, upgrade, rollback
    credential_store: Windows current-user DPAPI, owner-only ACL, versioned blob, strict alias encoding, symlink/reparse rejection, bounded file size, atomic replacement, no plaintext fallback
    tls: production requires HTTPS, localhost HTTP only in explicit local mode, no insecure toggle, optional PKCS12 trust store overrides default JVM trust for the client
  dependency_decision:
    jna_platform: 5.19.1
    transitive_dependencies: net.java.dev.jna:jna 5.19.1
    jackson: 2.22.1 (databind/core resolved by local dependency tree; GitHub Snyk exact-head result NOT_PROVEN)
  corrective_local_verification:
    committed_head: 8e82517a06bf824823b0e357c9c088caeca3e1f1
    corrective_commit: NOT_CREATED
    core_targeted_p1e: 394/394 PASS
    core_full_mvn_test: PASS 2700 tests, 0 failures, 0 errors, 45 skipped
    operantctl_clean_verify: 43/43 PASS
    operantctl_packaged_behavioural_smokes: 8/8 PASS
    operantctl_jar_sha256: BB0E508913252C6FD00890E2A058991196A23F4761B15DB7EA156483E07BDFAC
    p1d_topology_validator: PASS
    frontend_full_verification: npm test 752/752 PASS; lint PASS; typecheck PASS; build PASS
  corrective_not_proven:
    production_management_ingress: NOT_PROVEN
    clean_host_runtime: NOT_PROVEN
    lifecycle_commands: NOT_IMPLEMENTED
    exact_head_ci: NOT_PROVEN
    remote_pr_checks: NOT_INSPECTED
    merge: NOT_PERFORMED
open_p1:
  - P1-E lifecycle operations slice: logs, backup, restore, upgrade, rollback with state machine, idempotency, concurrency control, audit, redaction, fixed executor contracts, and P1-H runtime recovery proof
  - P1-F/P1-G real agent registry and agent status source
  - real AI/provider registry and provider status source
  - final operantctl wiring after real lifecycle, agent, and provider sources exist
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
next_bounded_action: Owner stages the unstaged corrective set (Finding H + Autowired wiring fix, credential import hardening, control-header strip completeness, frontend-plan deletion, execution-state update), creates the corrective commit, then runs exact-head CI/PR review. GitHub remote status, production runtime, and lifecycle commands remain NOT_INSPECTED/NOT_PROVEN/NOT_IMPLEMENTED.