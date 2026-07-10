document_version: 1
updated_at: 2026-07-10T18:20:00Z
repository: akonPAPA/Operant
phase: 1
controller_prompt: docs/prompts/production/01_SERVER_PLATFORM_AND_CONTROL_PLANE.md
branch: feature/p1-a-production-truth-and-config
head_sha: 53bdf708c9a437ea66fcd17f0be67bd2bf12a3de
implementation_base_sha: 7f05a1751d04d22ef572d8d6aca0dcbdc457df72
upstream_sha: 7f05a1751d04d22ef572d8d6aca0dcbdc457df72
worktree_clean: true
current_pr: pending
last_merged_pr: "#262 proof/security: add live cockpit runbook and gate markers"
production_authentication:
  CURRENT_PRODUCTION_AUTH_MODE: SIGNED_GATEWAY_HEADERS
  OIDC_STATUS: NOT_IMPLEMENTED
  OIDC_ENABLED_IN_PRODUCTION: FAIL_CLOSED
  p1_c_replacement: Real OIDC/session identity mapping must replace temporary OIDC rejection
verified_gates: []
failed_gates:
  - id: P1-GATE-01
    status: PARTIAL_NOT_PASS
    evidence: [EV-P1A-001, EV-P1A-002]
    implementation_sha: 53bdf708c9a437ea66fcd17f0be67bd2bf12a3de
  - id: P1-GATE-02
    status: FAIL
    note: BFF not implemented (P1-B)
  - id: P1-GATE-03
    status: FAIL
    note: Direct Core exposure not remediated (P1-B/D)
blocked_gates: []
not_proven:
  - Next.js production environment validation
  - Clean-host production startup
  - CI on feature branch
  - Full Phase 1 gate matrix
open_p0: []
open_p1:
  - P1-B Browser/BFF boundary
  - P1-C Production OIDC identity
  - P1-D Linux deployment
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
open_infra: []
owner_decisions_required:
  - operantctl implementation language
  - operant-agent implementation language
next_bounded_action: Merge P1-A PR; then P1-B (do not start on this branch)
evidence_manifest_path: docs/production/RELEASE_EVIDENCE_MANIFEST.md
