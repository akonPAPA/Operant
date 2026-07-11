document_version: 2
updated_at: 2026-07-11T16:22:00Z
repository: akonPAPA/Operant
phase: 1
controller_prompt: docs/prompts/production/01_SERVER_PLATFORM_AND_CONTROL_PLANE.md
branch: feature/p1-b-browser-bff-boundary
head_sha: af01e52c0734397b71fb6d80adda30ffe5dcbf31
implementation_base_sha: 3fdf166b5429947532a2d535d3e3fcd9ab946a4b
upstream_sha: 3fdf166b5429947532a2d535d3e3fcd9ab946a4b
worktree_clean: true
current_pr: "#267"
last_merged_pr: "#266 p1-a-production-truth-and-config"
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
    status: PARTIAL_NOT_PASS
    evidence: [EV-P1B-005, EV-P1B-006, EV-P1B-007, EV-P1B-008, EV-P1B-010]
    implementation_sha: af01e52c0734397b71fb6d80adda30ffe5dcbf31
    note: Browser BFF boundary locally implemented and verified (Maven security tests run at af01e52 clean worktree); live Redis, P1-C identity, deployed topology, remote CI, and direct Core ingress closure are not proven here.
  - id: P1-GATE-03
    status: FAIL
    note: Direct Core exposure not remediated (P1-D)
blocked_gates: []
not_proven:
  - Remote CI on PR #267
  - Live Redis session TTL/expiry/revocation in deployed topology
  - Real production identity/OIDC and tenant membership mapping
  - Direct public Core ingress closure
  - Full Phase 1 gate matrix
open_p0: []
open_p1:
  - P1-B Browser/BFF boundary (local proof complete; PR #267 not landed)
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
next_bounded_action: Land PR #267 only after remote CI/security checks pass; do not start P1-C/P1-D in this slice.
evidence_manifest_path: docs/production/RELEASE_EVIDENCE_MANIFEST.md
