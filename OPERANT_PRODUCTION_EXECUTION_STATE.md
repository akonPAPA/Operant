document_version: 6
updated_at: 2026-07-17T10:10:54Z
repository: akonPAPA/Operant
phase: 1
branch: main
closed_pr: "#279"
base_sha_before_resolution: 502f6c0665909a830d22b36f4dad34d75266b6c2
implementation_source_head: 91151672363b4ea0cfa00e03a1851206a7fd09a9
final_verified_pr_head: 3d0f5fc7eb0e8bec80cae2d71199efb02f1e2d33
merge_commit_sha: 689c1d2a3cdb7fdb3c8868508a101cf769871d2d
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
exact_head_verification:
  head_sha: 3d0f5fc7eb0e8bec80cae2d71199efb02f1e2d33
  frontend: PASS
  backend: PASS
  ci: PASS
  ai_worker: PASS
  semgrep: PASS
  snyk: PASS
not_proven:
  - real identity-provider interoperability
  - deployed Redis failover and expiry behavior
  - DNS-pinned outbound identity-provider connections
  - clean-host Linux deployment
  - public Core ingress closure
  - independent external-customer authentication flow
  - independent service-account authentication flow
  - independent Operant support and maintenance authentication flow
open_p1:
  - P1-D Linux deployment and public Core ingress closure
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
next_bounded_action: P1-D Linux deployment, production topology and public Core ingress closure.
