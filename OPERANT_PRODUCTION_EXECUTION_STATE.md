document_version: 5
updated_at: 2026-07-17T00:00:00Z
repository: akonPAPA/Operant
phase: 1
branch: feat/p1-c-full-rebuilt
current_pr: "#279"
base_sha_before_resolution: 502f6c0665909a830d22b36f4dad34d75266b6c2
implementation_source_head: 91151672363b4ea0cfa00e03a1851206a7fd09a9
final_pr_head: RECORDED_IN_PR_COMMENT
production_authentication:
  core_auth_mode: SIGNED_GATEWAY_HEADERS
  browser_auth_mode: BFF_OIDC_AUTHORIZATION_CODE
  p1_c_code_status: IMPLEMENTED_PENDING_EXACT_HEAD_CI
  runtime_implemented: true
  login_callback_pkce_state_nonce: IMPLEMENTED
  browser_login_binding: IMPLEMENTED
  configured_tenant_mapping: IMPLEMENTED
  production_session_store: REDIS_REQUIRED
  bff_to_core_authority: SERVER_RESOLVED_AND_SIGNED
  liveness_route: /api/bff/health
  readiness_route: /api/bff/ready
not_proven:
  - exact-head CI after conflict resolution
  - real identity-provider interoperability
  - deployed Redis failover and expiry behavior
  - DNS-pinned outbound identity-provider connections
  - clean-host Linux deployment
  - public Core ingress closure
open_p1:
  - P1-D Linux deployment and public Core ingress closure
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
next_bounded_action: P1-D after PR 279 exact-head verification.
