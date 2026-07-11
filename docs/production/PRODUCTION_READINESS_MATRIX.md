# Production Readiness Matrix

**Anchor commit (base):** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (`main`, merged PR #262)

| Gate ID | Capability | Status @ base SHA | Evidence @ base SHA | Current delta |
| --- | --- | --- | --- | --- |
| P1-GATE-01 | Production config rejects demo/insecure defaults | **PARTIAL / NOT_PASS** @ `53bdf70` | EV-P1A-001, EV-P1A-002 | P1-A merged in PR #266; clean-host deploy still not full gate |
| P1-GATE-02 | Browser traffic mediated by BFF | FAIL | Direct `NEXT_PUBLIC_CORE_API_URL` clients | **PARTIAL / NOT_PASS** locally on PR #267 branch @ `af01e52`: same-origin `/api/bff`, server session authority, default-deny route registry, CSRF mutation guard, safe Core error mapping, local Chromium E2E 9/9, Maven targeted/broader security tests (285/470 pass at clean af01e52); remote CI/live Redis/P1-C identity/deployed topology not proven |
| P1-GATE-03 | Direct public Core access denied | FAIL | Compose publishes Core :8080 | Still not pass; public Core ingress closure belongs to P1-D |
| P1-GATE-04 | Sessions expire/revoke | FAIL | No session layer | Unit/fake-store proof only; live Redis proof not complete |
| P1-GATE-05 | Tenant/staff/service identity separation | PARTIAL | Support grant + permission tests | P1-C identity mapping still required |
| P1-GATE-06 | Linux production deployment | FAIL | Local compose only | Not in P1-B scope |
| P1-GATE-07 | Readiness reflects dependencies | PARTIAL | Actuator health | Not in P1-B scope |
| P1-GATE-08 | operantctl | FAIL | Not in repository | Not in P1-B scope |
| P1-GATE-09 | Connector Gateway | FAIL | Not in repository | Not in P1-B scope |
| P1-GATE-10 | operant-agent | FAIL | Not in repository | Not in P1-B scope |
| P1-GATE-11 | Cross-platform agent packages | FAIL | Not in repository | Not in P1-B scope |
| P1-GATE-12 | Backup/restore/upgrade executed | FAIL | Local runbook only | Not in P1-B scope |
| P1-GATE-13 | Blocking CI/security gates | NOT_PROVEN | Pilot gate on main; not re-run in P1-B pass | PR #267 workflow includes blocking frontend E2E, but remote GitHub checks were not inspected here |

**Rule:** No gate may be marked PASS without an evidence row in `RELEASE_EVIDENCE_MANIFEST.md` tied to the exact implementation commit.
