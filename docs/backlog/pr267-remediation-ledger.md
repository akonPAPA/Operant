# PR #267 Root-Cause Remediation Ledger (F01–F16)

> **STATUS: NON-AUTHORITATIVE / WORKING-TREE EVIDENCE.**
> These changes are uncommitted in the working tree on branch `fix/pr267-runtime-root-causes`.
> No SHA is bound yet: this is NOT release evidence. After the owner commits and CI re-runs at an
> exact head SHA, the exact-head evidence (RELEASE_EVIDENCE_MANIFEST) becomes authoritative.

- Audit base SHA: `cae9603c870eeb0e87216d0f4707169b64eb2ea3`
- Audited head SHA (branch start point): `a08a0c896ac2c16e75ac725971ecefdae76f239d`
- Implementation branch: `fix/pr267-runtime-root-causes` (uncommitted working tree)
- SHA classes (F12): implementation SHA = (uncommitted), tested SHA = (uncommitted worktree),
  PR head SHA = NOT YET CREATED, merge SHA = N/A, workflow source SHA = N/A.

Status vocabulary: **CLOSED** (implemented + proven by tests run locally), **PARTIAL** (safely-
completable slice implemented + proven; a bounded remainder is explicitly deferred/fail-closed),
**BLOCKED** (cannot be verified in this environment; owner action required).

## PR A — runtime root causes

| ID | Title | Status | Key files | Proof |
| --- | --- | --- | --- | --- |
| F01 | Idempotency-Key fails closed + BFF/Core grammar parity | CLOSED | `lib/bff/bff-idempotency-key.ts`, `idempotency-key-contract.json`, `bff-route-registry.ts`, `bff-proxy.ts`, `common/api/ClientIdempotencyKey.java` | `bff-proxy-boundary.test.mjs` (8 F01 tests), `ClientIdempotencyKeyContractParityTest` (3) |
| F02 | Production-safe multipart intake | PARTIAL | `application.yml` (multipart bounds), `bff-route-registry.ts` (no upload route) | `bff-proxy-boundary.test.mjs` F02 (browser upload fail-closed, zero Core calls); `ObjectStorageServiceTest` (11). **Deferred:** a dedicated BFF streaming multipart adapter + browser upload UI — the browser upload path stays fail-closed (no upload route registered). Core JSON intake through the signed gateway is already size/type/extension-validated and hashes independently via `ObjectStorageService`. |
| F03 | Bounded RSC error contract | CLOSED | `lib/safe-server-error.ts`, `lib/server/tenant-get-json.server.ts` | `safe-server-error.test.mjs` (5) — hostile strings never reach the returned error |
| F04 | Cheap denials never touch Redis; bounded reconnect | CLOSED | `bff-proxy.ts` (`validateImmutableBffConfig`), `bff-session-store.ts` (single in-flight connect) | `bff-proxy-boundary.test.mjs` F04 (3): malformed path/route/cookie → 0 Redis connects; Redis-down → 1 bounded connect, 0 Core calls |
| F05 | Query contract parity (zero-based page/offset) | CLOSED | `bff-route-registry.ts`, `bff-proxy.ts` (`non-negative-int`/`bounded-int`) | `bff-proxy-boundary.test.mjs` F05 (2): page=0/offset=0 valid; negative/overflow/exponent/decimal/dup rejected |
| F06 | Hard browser/server module separation | CLOSED | `lib/bff/bff-public-config.ts` (new), `bff-config.ts` (re-export), browser modules repointed | `browser-server-boundary.test.mjs` (2): transitive graph from browser entrypoints reaches no server-only/Node/Buffer/gateway modules |
| F08 | Consistent Secure cookie policy | CLOSED | `bff-deployment-profile.ts` (`isSecureCookieDeployment`), `bff-config.ts` | `bff-cookie-secure-policy.test.mjs` (3): profile × NODE_ENV matrix; issuance/clearing share one predicate |
| F09 | Security headers on every middleware branch + safe redirect | CLOSED | `lib/edge-middleware-core.ts` (new), `proxy.ts` | `edge-middleware-core.test.mjs` (5): all branches header-wrapped; redirect preserves safe path+query, strips external |

## PR B — proof / security closure

| ID | Title | Status | Key files | Proof |
| --- | --- | --- | --- | --- |
| F07 | Shared bounded UI error mapper | CLOSED | `lib/ui-error.ts` (new; incl. `BoundedUiError` typed passthrough) + 12 browser API clients migrated + component-layer catches fixed (`intake-upload-form.tsx`, `quote-workspace.tsx`, `quote-review-cockpit.tsx`, `quote-source-context-panel.tsx`, `lib/operator-action-runtime.ts`, `lib/quote-review-api.ts`, `lib/quote-transaction-api.ts`) | `ui-error.test.mjs` (4); the AST/source guard now scans ALL `components/*.tsx` + API clients + operator-action runtime for raw exception passthrough |
| F10 | Correct permission-tampering proof | CLOSED | `ApiGatewayHeaderAuthenticationHardeningTest.java` | 7/7; the proof now changes ONLY the permission header after signing (empty-body POST removes the body-hash confound), asserts 401 + zero handler invocation, and the untouched control succeeds |
| F11 | Full tracked-file secret scanner | CLOSED | `scripts/check-no-secrets.ps1` | `-SelfTest` passes (prod/test/e2e detection, example/test-only lines detected, exact-fingerprint allow, changed-fixture detected, PEM, redaction, fail-closed); real scan clean (exit 0). Scans test/e2e/docs; broad word suppression removed; 20-entry SHA-256 fingerprint allowlist. Fixed a case-insensitivity false-negative. |
| F12 | Exact-head evidence integrity + ledger | CLOSED (this doc) | this ledger, `scripts/check-evidence-integrity.mjs`, `RELEASE_EVIDENCE_MANIFEST.md` | `check-evidence-integrity.mjs` fails on unresolved markers / PASS-without-proof; placeholder removed |
| F13 | Isolate E2E build artifacts (dev vs standalone `.next`) | CLOSED | `next.config.mjs` (validated env-driven `distDir`), `e2e/dev-server.mjs` (isolated `.next-e2e-dev`), `e2e/run-e2e.mjs` (SHA-256 artifact-manifest integrity check, deterministic pre/post cleanup, tracked-file restore), `e2e/standalone-server.mjs` (idempotent asset prep), `e2e/p1b-bff-boundary.spec.ts` (sign-in hydration-race + root-redirect fix), `.gitignore` (`.next-*/`), `eslint.config.mjs` | `npm run build` exit 0; `npm run test:e2e` 10/10 passed twice consecutively (exit 0, deterministic); the runner hashes every file under `.next` after build and re-hashes after Playwright — production artifact byte-identical across both runs (4279 files); dev writes only the isolated gitignored `.next-e2e-dev`, removed before and after every run |
| F14 | Transitive RSC import graph | CLOSED | `tests/rsc-transitive-import-guard.test.mjs`, `tests/fixtures/rsc-negative/*` | 3/3: catches deep page→component→helper→browser-api chain (prints chain), stops at `use client` / `.server.ts` boundaries, no real-app violation |
| F15 | Generated Next file cleanliness | CLOSED | `tests/worktree-cleanliness.test.mjs` (next-env.d.ts diff + canonical form + tsconfig distDir-leak guard), `e2e/run-e2e.mjs` (snapshot/restore of tracked generated sources) | Proven after full `next build` + Playwright E2E locally: `git diff --exit-code -- next-env.d.ts tsconfig.json` clean (exit 0) and the `git status --porcelain --untracked-files=all` pre-build vs post-E2E baseline comparison shows no new generated or untracked files (the dev runtime rewrites next-env.d.ts/tsconfig.json for the isolated distDir; the runner restores both deterministically). CI re-enforces the same check after build/E2E. |
| F16 | Machine-enforced BFF/Core route parity | CLOSED | `lib/bff/bff-tenant-routes.generated.json`, `tests/bff-route-artifact.test.mjs`, `BffCoreRoutePolicyParityTest.java` | Frontend drift guard (2) + Core parity (1): all 143 BFF routes match the REAL `ApiRouteSecurityPolicy.classify()` permission exactly, tenant plane only, correct read/mutation class |

## Explicitly NOT_PASS (unproven production gates — unchanged by this remediation)

- P1-C real identity provider; P1-D public Core ingress
- Live Redis topology / deployed production startup on a clean host
- Production streaming multipart intake topology (F02 remainder)
- Operant support/staff identity plane in production
- Remote CI green at an exact committed head SHA (nothing is committed yet)

## Plane separation (unchanged, re-verified by F16)

Tenant User Access, External Customer Access, Service Account Access, and the Operant Support &
Maintenance plane remain separate. F16 proves no `STAFF_*`/support/internal/public route is reachable
through the tenant-operator BFF; the BFF registry is a strict, generated subset of Core policy.
