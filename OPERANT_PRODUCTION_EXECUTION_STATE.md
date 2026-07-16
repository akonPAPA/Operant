document_version: 4
updated_at: 2026-07-16T00:00:00Z
repository: akonPAPA/Operant
phase: 1
controller_prompt: docs/prompts/production/01_SERVER_PLATFORM_AND_CONTROL_PLANE.md
branch: feat/p1-c-full-rebuilt
head_sha: 740f369a17bb6494931d59340194980c9b59c1dd
worktree_clean: false
current_pr: "#277"
last_merged_pr: "#271"
evidence_basis: build/review/pr266-pr277-closure-ledger.md
production_authentication:
  CURRENT_PRODUCTION_AUTH_MODE: SIGNED_GATEWAY_HEADERS
  P1-C OIDC CONFIGURATION AND DISCOVERY FOUNDATION: LOCAL_PROOF_COMPLETE_PENDING_COMMIT_AND_REMOTE_EXACT_HEAD_PROOF
  P1-C PRODUCTION IDENTITY: NOT_COMPLETE
  OIDC_ENABLED_IN_PRODUCTION: FAIL_CLOSED
  OIDC_RUNTIME_IMPLEMENTED: false
  login_route: NOT_IMPLEMENTED
  callback_route: NOT_IMPLEMENTED
  token_exchange: NOT_IMPLEMENTED
  id_token_validation: NOT_IMPLEMENTED
  tenant_membership_mapping: NOT_IMPLEMENTED
  staff_identity_flow: NOT_IMPLEMENTED
  service_account_separation: NOT_FULLY_INTEGRATED
  production_session_issuance: NOT_IMPLEMENTED
  controlled_connection_pinned_egress: NOT_IMPLEMENTED
verified_gates:
  - id: P1-C-OIDC-CONFIGURATION-AND-DISCOVERY-FOUNDATION-LOCAL
    status: LOCAL_PROOF_COMPLETE_PENDING_COMMIT_AND_REMOTE_EXACT_HEAD_PROOF
    evidence:
      - apps/web-dashboard/.logs/node-oidc-targeted.log
      - apps/web-dashboard/.logs/npm-typecheck-p1c.log
      - apps/web-dashboard/.logs/npm-lint-p1c.log
      - apps/web-dashboard/.logs/npm-test-p1c.log
      - apps/web-dashboard/.logs/npm-build-p1c.log
      - apps/web-dashboard/.logs/npm-e2e-p1c.log
    note: Local dirty-worktree proof only; no commit or remote exact-head CI proof yet.
failed_gates:
  - id: P1-GATE-01
    status: PARTIAL_NOT_PASS
    note: Production-like Core config fail-closed proven historically; clean-host deploy not proven.
  - id: P1-GATE-02
    status: PARTIAL_NOT_PASS
    note: Browser BFF boundary proven historically and current web gates pass locally; live Redis topology and full P1-C identity not complete.
  - id: P1-GATE-03
    status: FAIL
    note: Direct Core exposure not remediated; belongs to P1-D.
  - id: P1-GATE-04
    status: PARTIAL_NOT_PASS
    note: Unit/fake-store session TTL/expiry/revocation proven historically; live Redis not proven.
  - id: P1-GATE-05
    status: PARTIAL_NOT_PASS
    note: Full tenant/staff/service identity separation is not complete until production OIDC identity and membership mapping are implemented.
blocked_gates: []
not_proven:
  - Remote exact-head PR #277 CI, review-thread and workflow evidence
  - Commit-linked immutable evidence for the current local P1-C hardening patch
  - Login route
  - Callback route
  - Token exchange
  - ID-token validation
  - Tenant membership mapping
  - Staff identity flow
  - Service-account separation fully integrated with production identity
  - Production authenticated session issuance
  - Controlled connection-pinned egress / DNS rebinding resistance
  - Live Redis session TTL/expiry/revocation in deployed topology
  - Direct public Core ingress closure (P1-D)
explicit_non_claims:
  - P1-C complete
  - production OIDC complete
  - production identity complete
  - OIDC runtime complete
  - DNS rebinding solved
  - connection pinning proven
  - production authenticated sessions implemented
open_p0: []
open_p1:
  - P1-C Production OIDC identity
  - P1-D Linux deployment / public Core ingress closure
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
open_infra: []
owner_decisions_required:
  - Controlled connection-pinned egress transport design for production OIDC discovery
  - P1-C2 transaction store choice if existing Redis/session semantics cannot provide atomic one-time state consumption
next_bounded_action: P1-C2 OIDC Authorization Transaction Foundation; do not implement P1-D.
evidence_manifest_path: docs/production/RELEASE_EVIDENCE_MANIFEST.md
manual_commit_command: |
  git add apps/web-dashboard/lib/bff/bff-oidc-config.ts apps/web-dashboard/lib/bff/bff-oidc-runtime-network.ts apps/web-dashboard/lib/bff/bff-oidc-runtime.ts apps/web-dashboard/tests/bff-oidc-config-contract.test.mjs apps/web-dashboard/tests/bff-oidc-runtime-contract.test.mjs OPERANT_PRODUCTION_EXECUTION_STATE.md
  git commit -m "fix(identity): harden OIDC discovery provenance, egress and cache"