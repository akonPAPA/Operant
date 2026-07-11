document_version: 2
updated_at: 2026-07-11T18:45:00Z
repository: akonPAPA/Operant
phase: 1
controller_prompt: docs/prompts/production/01_SERVER_PLATFORM_AND_CONTROL_PLANE.md
branch: feature/p1-b-browser-bff-boundary
head_sha: 6d991ec5edfe044c66768e8c3a8d2d8645be9f85
implementation_base_sha: 34099fd7b5328536cc26b35955a064561d7148f7
implementation_sha: 1210f94e841c4f7103b4f1ff16330dd0fdf30fb8
evidence_docs_sha: 6d991ec5edfe044c66768e8c3a8d2d8645be9f85
upstream_sha: 3fdf166b5429947532a2d535d3e3fcd9ab946a4b
worktree_clean: true
current_pr: "#267"
last_merged_pr: "#266 p1-a-production-truth-and-config"
remote_ci_status: NOT_RUN_FOR_FINAL_IMPLEMENTATION_SHA
push_performed: false
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
    evidence: [EV-P1B-012, EV-P1B-013, EV-P1B-014, EV-P1B-015, EV-P1B-016, EV-P1B-017, EV-P1B-018]
    implementation_sha: 1210f94e841c4f7103b4f1ff16330dd0fdf30fb8
    note: Browser BFF boundary implemented and verified on clean immutable SHA (586 Node tests, lint/tsc/build, standalone E2E 9/9, Core security 285+470 @ JDK 21). EV-P1B-005..009 marked SUPERSEDED (dirty-tree). Live Redis, P1-C identity, deployed topology, and remote CI not proven.
  - id: P1-GATE-03
    status: FAIL
    note: Direct Core exposure not remediated (P1-D)
  - id: P1-GATE-04
    status: PARTIAL_NOT_PASS
    evidence: [EV-P1B-015]
    implementation_sha: 1210f94e841c4f7103b4f1ff16330dd0fdf30fb8
    note: Session TTL/expiry/revocation proven in unit/fake-store only; live Redis not proven.
blocked_gates: []
not_proven:
  - Remote CI on PR #267 @ implementation_sha 1210f94
  - Live Redis session TTL/expiry/revocation in deployed topology
  - Real production identity/OIDC and tenant membership mapping
  - Direct public Core ingress closure
  - Full Phase 1 gate matrix
open_p0: []
open_p1:
  - P1-B Browser/BFF boundary (local immutable proof @ 1210f94; PR #267 not pushed/merged)
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
next_bounded_action: Push PR #267 and require remote CI/security checks on exact head; do not mark production PASS until P1-C/P1-D gaps close.
evidence_manifest_path: docs/production/RELEASE_EVIDENCE_MANIFEST.md
