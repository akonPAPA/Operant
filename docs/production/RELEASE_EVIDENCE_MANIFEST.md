# Release Evidence Manifest

**Base anchor SHA:** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (`main`, PR #262)
**P1-A implementation SHA:** `53bdf708c9a437ea66fcd17f0be67bd2bf12a3de` (`feature/p1-a-production-truth-and-config`)

## PR #267 immutable anchors (browser/BFF boundary)

| Field | SHA / status |
| --- | --- |
| `base_sha` (`origin/main`) | `cae9603c870eeb0e87216d0f4707169b64eb2ea3` |
| `implementation_sha` | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` |
| `evidence_commit` | resolve after push with `git rev-parse HEAD` (not self-referenced inside this commit) |
| `pr_head_sha` | resolve after evidence push |
| `remote_ci_status` (implementation SHA) | **PASS** (all required workflows SUCCESS @ `09d8a08`) |
| `remote_ci_head_sha` | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` |
| `push_performed` | `true` (implementation pushed; evidence commit follows) |
| `local_implementation_tests` | EV-P1B-025..028 PASS; remote exact-head CI PASS for implementation SHA |
| `merge_ready_claim` | **not claimed in Stage B docs alone** — see final report after evidence-head CI |

### Exact-head CI @ `09d8a08` (implementation)

| Workflow | Run ID | Result | Notes |
| --- | --- | --- | --- |
| Frontend | [29197549322](https://github.com/akonPAPA/Operant/actions/runs/29197549322) | SUCCESS | build job: lint/tsc/build/npm test + Playwright BFF E2E |
| CI | [29197549317](https://github.com/akonPAPA/Operant/actions/runs/29197549317) | SUCCESS | Backend tests, Docker compose, Core release Docker guard, Stage 11C |
| Backend | [29197549307](https://github.com/akonPAPA/Operant/actions/runs/29197549307) | SUCCESS | backend-integration-tests |
| Semgrep Security Scan | [29197549306](https://github.com/akonPAPA/Operant/actions/runs/29197549306) | SUCCESS | SAST + gate |
| Snyk Dependency Scan | [29197549337](https://github.com/akonPAPA/Operant/actions/runs/29197549337) | SUCCESS | web-dashboard scan + gate; **core-api Snyk job SKIPPED** (path filter / no core-api lock change) |
| AI Worker | [29197549311](https://github.com/akonPAPA/Operant/actions/runs/29197549311) | SUCCESS | Gate PASS; **test job SKIPPED** (AI worker paths unchanged) |
| PR #267 / CodeQL | [29197548275](https://github.com/akonPAPA/Operant/actions/runs/29197548275) | SUCCESS | Analyze actions/java/js/python + CodeQL |

PR proves **P1-B browser/BFF boundary**, not full production readiness (P1-C identity + P1-D public Core ingress remain).


| Evidence ID | Commit SHA | Type | Command / artifact | Result | Gates supported |
| --- | --- | --- | --- | --- | --- |
| EV-P1A-001 | `53bdf708c9a437ea66fcd17f0be67bd2bf12a3de` | junit | `cd apps/core-api && mvn -Dtest=ProductionConfigurationValidatorTest,GatewayHeaderAuthProductionGuardTest,ProductionAuthenticationReadinessGuardTest,ProductionIntakeSecurityGuardTest test` | BUILD SUCCESS; **36** tests, 0 failures (`apps/core-api/.logs/mvn-p1a-config.log`) | P1-GATE-01 **PARTIAL / NOT_PASS** |
| EV-P1A-003 | `3fdf166b5429947532a2d535d3e3fcd9ab946a4b` | github-actions | PR #266 CI on `feature/p1-a-production-truth-and-config` @ `7c178e2` (Backend tests, CI, CodeQL, Semgrep, Snyk — all SUCCESS) | MERGED to `main` @ `3fdf166` (`gh pr view 266 --json state,mergedAt,mergeCommit,headRefOid`) | P1-GATE-01 **PARTIAL / NOT_PASS** (CI proven; clean-host deploy not proven) |
| EV-P1B-001 | `ea543d717219ec91d572b16bb4e086b7e35ee4e3` | node-test + tsc + lint + build | `cd apps/web-dashboard && npm test && npm run typecheck && npm run lint && npm run build` | **553** node tests pass; typecheck/lint/build exit 0; **zero** Next.js Edge Runtime warnings (no `node:crypto`/`redis` in middleware graph), 2026-07-10 | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-002 | `ea543d717219ec91d572b16bb4e086b7e35ee4e3` | behavioral unit | `tests/bff-session-lifecycle.test.mjs`, `tests/bff-proxy-boundary.test.mjs`, `tests/bff-boundary.test.mjs`, `tests/bff-transport-contract.test.mjs`, `tests/edge-middleware-imports.test.mjs`, `tests/login-redirect.test.mjs` (production bootstrap denial + no cookies on failure; Redis mandatory in production, no memory fallback, Redis errors fail closed; expiry/revocation/rotation/logout revocation; default-deny registry incl. wrong-method + internal/support/staff/demo/webhook/public denial; authority/response header stripping; CSRF missing/mismatch/malformed/cross-origin → 403 with upstream fetch count 0; valid mutation reaches upstream exactly once; redirect validation; deterministic `/api/bff` browser transport; Edge middleware import graph clean) | Pass | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-003 | `ea543d717219ec91d572b16bb4e086b7e35ee4e3` | playwright browser E2E | `cd apps/web-dashboard && npm run test:e2e` (Chromium, production non-demo build, bounded fake Core on 127.0.0.1:18080, local-test profile app :3100 + production profile app :3101) | **9/9 scenarios pass** — login redirect, production sign-in fails closed, local bootstrap session, reads only via `/api/bff` with server-signed authority, invalid CSRF denied (0 Core calls), valid CSRF reaches Core exactly once, logout revocation + old-cookie reuse fails, internal support unreachable, raw Core errors/headers not exposed | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-004 | `ea543d717219ec91d572b16bb4e086b7e35ee4e3` | junit | `cd apps/core-api && mvn -Dtest=ProductionConfigurationValidatorTest,GatewayHeaderAuthProductionGuardTest,GatewayHeaderReplayProtectionTest,ApiSecurityWebConfigTest,ApiHeaderAuthenticationFilterDisabledModeTest,TrustedGatewaySignerVerifierCompatibilityTest test` | BUILD SUCCESS; **58** tests, 0 failures — includes the cross-language gateway signature fixture (`docs/security/gateway-signature-fixture.json`) verified by both the TypeScript signer test and the Java verifier | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-005 | `e292c1320061aab6c8affdbcfb76c808871ea9c1` + working tree | node-test | `cd apps/web-dashboard && npm test` | **557** tests pass | **SUPERSEDED — NON-AUTHORITATIVE — NOT RELEASE EVIDENCE** (dirty worktree; replaced by EV-P1B-012 @ `1210f94`) |
| EV-P1B-006 | `e292c1320061aab6c8affdbcfb76c808871ea9c1` + working tree | lint + tsc + build | `cd apps/web-dashboard && npm run lint && npm run typecheck && npm run build` | All exit 0. Build warning noted: Next.js `middleware` file convention is deprecated. | **SUPERSEDED — NON-AUTHORITATIVE — NOT RELEASE EVIDENCE** (dirty worktree; replaced by EV-P1B-013 @ `1210f94`) |
| EV-P1B-007 | `e292c1320061aab6c8affdbcfb76c808871ea9c1` + working tree | playwright browser E2E | `cd apps/web-dashboard && npm run test:e2e` | **9/9** scenarios pass after installing local Playwright Chromium. Warnings noted: `next start` with standalone output, Node DEP0190 from the E2E runner shell usage. | **SUPERSEDED — NON-AUTHORITATIVE — NOT RELEASE EVIDENCE** (dirty worktree + `next start`/shell runner; replaced by EV-P1B-014 @ `1210f94`) |
| EV-P1B-008 | `e292c1320061aab6c8affdbcfb76c808871ea9c1` + working tree | behavioral unit | `cd apps/web-dashboard && node --test tests/edge-middleware-imports.test.mjs tests/frontend-authority.test.mjs tests/bff-boundary.test.mjs tests/bff-proxy-boundary.test.mjs tests/bff-transport-contract.test.mjs` | **50/50** tests pass; covers production BFF authority mode without private browser env flag, API JSON 401 vs page redirect, UUID-only canonical dynamic IDs, typed query allowlists, safe upstream error mapping, response/body bounds, static alias denial, and middleware import graph. | **SUPERSEDED — NON-AUTHORITATIVE — NOT RELEASE EVIDENCE** (dirty worktree; replaced by EV-P1B-015 @ `1210f94`) |
| EV-P1B-009 | `e292c1320061aab6c8affdbcfb76c808871ea9c1` + working tree | junit | `cd apps/core-api && mvn "-Dtest=TrustedGatewaySignerVerifierCompatibilityTest,ApiRouteSecurityPolicyDefaultDenyTest,ApiRouteSecurityClassificationTest,ApiPermissionInterceptorPermissionTest,ApiPermissionRouteCoverageTest" test` and `cd apps/core-api && mvn "-Dtest=com.orderpilot.security.*Test" test` | Targeted route/security slice: **272** tests pass. Wider security package: **470** tests pass. | **SUPERSEDED — NON-AUTHORITATIVE — NOT RELEASE EVIDENCE** (dirty worktree; replaced by EV-P1B-016/017 @ `1210f94`) |
| EV-P1B-010 | `af01e52c0734397b71fb6d80adda30ffe5dcbf31` | junit | `cd apps/core-api && mvn clean "-Dtest=TrustedGatewaySignerVerifierCompatibilityTest,ApiRouteSecurityPolicyDefaultDenyTest,ApiRouteSecurityClassificationTest,ApiPermissionInterceptorPermissionTest,ApiPermissionRouteCoverageTest,GatewayHeaderReplayProtectionTest,ApiSecurityWebConfigTest" test` | Targeted route/security slice (immutable clean worktree at af01e52): **285** tests pass, 0 failures. | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-011 | `af01e52c0734397b71fb6d80adda30ffe5dcbf31` | junit | `cd apps/core-api && mvn "-Dtest=com.orderpilot.security.*Test" test` (immutable af01e52) | Broader security package: **470** tests pass, 0 failures, 0 skipped. Maven version: 3.9.16, Java version: 21.0.11 (Eclipse Adoptium Temurin). | **SUPERSEDED for PR #267 final slice** — retained for history; active gate evidence: EV-P1B-017 @ `1210f94` |
| EV-P1B-012 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | node-test | `cd apps/web-dashboard && npm ci && npm test` (clean worktree) | **586** tests pass, 0 fail, 0 skip; exit 0 | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-013 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | lint + tsc + build | `cd apps/web-dashboard && npm run lint && npm run typecheck && npm run build` (after `npm ci`) | All exit 0; Next.js build compiled successfully | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-014 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | playwright browser E2E | `cd apps/web-dashboard && npm run test:e2e` — standalone `server.js` via `e2e/standalone-server.mjs`, `shell: false`, bounded fake Core | **9/9** pass (10.2s); no `next start` in runner | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-015 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | behavioral unit | `tests/bff-server-transport-isolation.test.mjs`, `tests/browser-csrf-cookie.test.mjs`, `tests/bff-session-ttl-policy.test.mjs`, `tests/bff-boundary.test.mjs`, `tests/bff-proxy-boundary.test.mjs`, `tests/bff-session-lifecycle.test.mjs`, `tests/bff-transport-contract.test.mjs`, `tests/e2e-standalone-runner.test.mjs` | Request-scoped in-process BFF isolation (concurrent tenants); canonical CSRF + duplicate security-cookie fail-closed; strict session TTL policy; stateless HMAC session tokens rejected in production paths; architecture import guards | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-016 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | junit | `cd apps/core-api && mvn clean "-Dtest=TrustedGatewaySignerVerifierCompatibilityTest,ApiRouteSecurityPolicyDefaultDenyTest,ApiRouteSecurityClassificationTest,ApiPermissionInterceptorPermissionTest,ApiPermissionRouteCoverageTest,GatewayHeaderReplayProtectionTest,ApiSecurityWebConfigTest" test` (JDK 21.0.11) | **285** tests, 0 failures (`apps/core-api/.logs/mvn-targeted1.log`) | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-017 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | junit | `cd apps/core-api && mvn "-Dtest=com.orderpilot.security.*Test" test` (JDK 21.0.11) | **470** tests, 0 failures, 0 skipped (`apps/core-api/.logs/mvn-security-all.log`) | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-018 | `1210f94e841c4f7103b4f1ff16330dd0fdf30fb8` | behavioral unit | `internal-support-operations-api` + BFF registry tests | Support plane returns `SUPPORT_PLANE_NOT_CONFIGURED` under production BFF; tenant BFF cannot reach internal/support routes (also covered in E2E) | **SUPERSEDED** — see EV-P1B-022/E2E @ `09b8a98` |
| EV-P1B-019 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | node-test | `cd apps/web-dashboard && npm ci && npm test` | **601** pass, 0 fail | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-020 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | lint + tsc + build | `npm run lint && npm run typecheck && npm run build` | All exit 0 | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-021 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | playwright E2E | `npm run test:e2e` (standalone, `shell: false`) | **9/9** pass | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-022 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | behavioral unit | `bff-production-rsc-path.test.mjs`, `rsc-page-import-guard.test.mjs` | Production Server Component reads via `lib/server/*.server.ts` → `tenant-get-json.server` → in-process BFF (`dashboard-server-bff-fetch`); inbox `/api/v1/intake/messages` path; tenant isolation; fail-closed session/permission/route cases; no server `/api/bff` HTTP | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-023 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | junit | targeted security clean (JDK 21.0.11) | **285** tests, BUILD SUCCESS | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-024 | `09b8a98eeac574aea5ba8bdc6f84970c45d87764` | junit | `com.orderpilot.security.*Test` (JDK 21.0.11) | **470** tests, BUILD SUCCESS | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-025 | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` | node-test + lint + tsc + build + e2e | `cd apps/web-dashboard && npm test && npm run lint && npm run typecheck && npm run build && npm run test:e2e` | **624** node tests pass; lint/tsc/build exit 0; Playwright **9/9** pass | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-026 | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` | junit | `mvn -f apps/core-api/pom.xml -Dtest='com.orderpilot.security.*Test' test` | **471** tests, 0 failures, 0 skipped (gateway signature v2 + 64-hex secret) | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-027 | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` | junit | `mvn -f apps/core-api/pom.xml test` | **2371** tests, 0 failures, **45** skipped (Postgres `integration.testdb` profile not active locally — not treated as PASS proof) | P1-GATE-02 **PARTIAL / NOT_PASS** |
| EV-P1B-028 | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` | secrets | `pwsh -File ./scripts/check-no-secrets.ps1 -SelfTest` + full scan | Self-test PASS; scan PASS | hygiene |
| EV-P1B-029 | `09d8a08c3c43bfe014b4132690f6dd8bb5dc71c9` | github-actions | Exact-head CI workflows listed in anchors table above | All required workflows **SUCCESS**; skipped: AI Worker `test` (paths unchanged), Snyk `core-api` (path filter), Playwright report upload (success path) | P1-GATE-02 **PARTIAL / NOT_PASS** (remote CI proven for implementation SHA) |

## P1-GATE-01 status

| Status | **PARTIAL / NOT_PASS** |
| --- | --- |
| Proven at implementation SHA | Core API fail-closed startup validation for production-like Spring profiles; focused + broader security/configuration JUnit evidence above |
| Not proven | Next.js production environment validation; clean-host production startup with real deploy config; CI against this feature branch |
| Explicit non-claim | **PASS** not recorded for P1-GATE-01 |

## P1-GATE-02 / P1-GATE-03 / P1-GATE-04 status

| Gate | Status | Proven (local) | Not proven |
| --- | --- | --- | --- |
| P1-GATE-02 (browser BFF boundary) | **PARTIAL / NOT_PASS** | Immutable proof @ `09d8a08`: gateway signature v2 (body/query/content-type), 64-hex shared secret, `ORDERPILOT_PUBLIC_ORIGIN` CSRF, local-bootstrap-only secret rename; Node **624**; E2E **9/9**; Core security **471**; full Maven **2371**/45 skipped; remote exact-head CI SUCCESS | P1-C identity; live Redis topology; P1-D public Core ingress; full production deploy |
| P1-GATE-03 | **NOT_PASS** | — | Public Core ingress closure belongs to P1-D |
| P1-GATE-04 | **PARTIAL / NOT_PASS** | Unit/fake-store session TTL, expiry, revocation, logout; strict TTL parser; duplicate cookie fail-closed | Live Redis expiry/revocation in deployed topology |

**Update policy:** Append rows with exact `git rev-parse HEAD` used for each test run. Do not mark PASS without `BUILD SUCCESS` and gate-specific runtime proof.
