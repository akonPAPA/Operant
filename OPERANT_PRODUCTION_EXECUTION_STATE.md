document_version: 7
updated_at: 2026-07-17T14:45:00Z
repository: akonPAPA/Operant
phase: 1
branch: fix/p1d-post-merge-root-causes
current_main_sha: 52e23746f23289b0a74f4ee5c0fccef3e2984812
last_merged_pr: "#281"
last_closed_capability: P1-D initial topology (merged, corrective delta pending)
active_capability: P1-D-CORRECTIVE
active_branch: fix/p1d-post-merge-root-causes
active_start_sha: 52e23746f23289b0a74f4ee5c0fccef3e2984812
active_head_sha: 52e23746f23289b0a74f4ee5c0fccef3e2984812 (corrective delta staged, uncommitted)
next_capability: P1-E operantctl and bounded Control API (after P1-D corrective merge)
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
p1d_corrective:
  state: IMPLEMENTED_LOCAL_VERIFIED_COMMIT_BLOCKED
  staged_patch_digest_sha256: 6243e1124bfbba3fe350568200ab71bafb9582a12ec1b1dedb20069236cb9582
  patch_artifact: .git/operant-recovery/p1d-interrupted/corrective-final.patch
  root_causes_closed:
    - executable deployment script (index mode 100755, test-asserted)
    - bounded startup timeout validation (30..900s, default 240, fail-closed parse)
    - compose up --wait health-gated startup, unhealthy exit propagated to systemd
    - systemd TimeoutStartSec=960 coherent with script max 900
    - structured ORDERPILOT_BFF_REDIS_HOST/PORT/PASSWORD replaces credential-bearing URL
    - credential-bearing Redis URL rejected fail-closed before connection
    - reserved-character password preserved exactly (no URL decode corruption)
    - no password leakage in error messages (test-asserted)
    - web-dashboard removed from data_private (BFF cannot reach PostgreSQL)
    - redis joined application_private (BFF and Core reachability)
    - exact network membership enforced by validator with negative mutations
    - stateful postgres/redis resource controls (no-new-privileges, pids, mem, cpus)
    - stop uses compose stop (no compose down, named volumes preserved)
    - fake-Docker lifecycle fault injection (unhealthy propagation, secret non-leak)
  verification:
    frontend_full_suite: 745/745 PASS
    p1d_topology_suite: 34/34 PASS
    typecheck: PASS
    lint: PASS
    backend_security_package: 525/525 PASS (com.orderpilot.security.**)
    real_compose_validate: PASS (Docker 29.6.1 / Compose v5.3.0, synthetic env, exit 0)
  blocked_transitions:
    - git commit (denied by .claude/settings.local.json permissions.deny)
    - git push (denied by .claude/settings.local.json permissions.deny)
    - gh pr create (denied by .claude/settings.local.json permissions.deny)
reconciliation_audit_2026_07_17:
  p1_a_config: CODE_PROVEN (backend production guards + validator tests green; frontend fail-closed config tests green)
  p1_b_bff_boundary: CODE_PROVEN (browser-server static boundary, proxy boundary, transport isolation green)
  p1_c_identity_planes: CODE_PROVEN (OIDC flow, session lifecycle, STAFF_*/tenant/service plane separation green)
  p1_d_topology: CODE_PROVEN_WITH_LOCAL_CORRECTIVE (validator + negative mutations + lifecycle fault injection green; corrective delta staged)
not_proven:
  - real identity-provider interoperability
  - deployed Redis failover and expiry behavior
  - DNS-pinned outbound identity-provider connections
  - clean-host Linux deployment and reboot lifecycle
  - public Core ingress closure on a real host
  - independent external-customer authentication flow
  - independent service-account authentication flow
  - independent Operant support and maintenance authentication flow
open_p1:
  - P1-D corrective merge (implemented, blocked on owner git permissions)
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
owner_decisions_required:
  - execute commit/push/PR of fix/p1d-post-merge-root-causes, or grant git commit/push/gh permissions for the program session
next_bounded_action: Merge P1-D corrective PR, then start P1-E operantctl and bounded Control API.
