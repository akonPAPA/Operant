document_version: 1
updated_at: 2026-07-10T00:00:00Z
repository: akonPAPA/Operant
phase: 1
controller_prompt: docs/prompts/production/01_SERVER_PLATFORM_AND_CONTROL_PLANE.md
branch: feature/p1-a-production-truth-and-config
head_sha: 7f05a1751d04d22ef572d8d6aca0dcbdc457df72
implementation_base_sha: 7f05a1751d04d22ef572d8d6aca0dcbdc457df72
upstream_sha: 7f05a1751d04d22ef572d8d6aca0dcbdc457df72
worktree_clean: false
current_pr: null
last_merged_pr: "#262 proof/security: add live cockpit runbook and gate markers"
verified_gates: []
failed_gates:
  - id: P1-GATE-01
    status: NOT_PASS
    note: P1-A startup validation implemented; partial evidence EV-P1A-001..004 on uncommitted working tree atop base SHA
  - id: P1-GATE-02
    status: FAIL
    note: BFF not implemented (P1-B)
  - id: P1-GATE-03
    status: FAIL
    note: Direct Core exposure not remediated (P1-B/D)
blocked_gates: []
not_proven:
  - Full Phase 1 gate matrix
  - Runtime clean-host Linux deployment
  - operantctl / operant-agent / Connector Gateway
open_p0: []
open_p1:
  - P1-B Browser/BFF boundary
  - P1-C Production OIDC identity
  - P1-D Linux deployment
  - P1-E operantctl
  - P1-F Connector Gateway protocol
  - P1-G operant-agent
  - P1-H Recovery and observability
open_infra:
  - OPERANT_PRODUCTION_EXECUTION_STATE must be updated with post-P1-A implementation SHA after merge
owner_decisions_required:
  - operantctl implementation language
  - operant-agent implementation language
next_bounded_action: Commit P1-A working tree; refresh manifest SHAs to implementation commit; then P1-B
evidence_manifest_path: docs/production/RELEASE_EVIDENCE_MANIFEST.md
