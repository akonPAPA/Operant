# PR #267 Root-Cause Remediation Ledger (F01–F16)

> **STATUS: PR #269 REMEDIATION LEDGER / LOCAL PATCH PENDING OWNER COMMIT.**
> Reviewed PR #269 head SHA: `99438a210a905939554f414d7f1038ef01e3b5a2` on branch
> `fix/pr267-runtime-root-causes`, stacked into PR #267. This document distinguishes the reviewed PR
> head from the post-fix implementation SHA, which must be filled by the owner after committing this
> remediation and re-running exact-head CI.

- Audit base SHA: `cae9603c870eeb0e87216d0f4707169b64eb2ea3`
- Audited head SHA (branch start point): `a08a0c896ac2c16e75ac725971ecefdae76f239d`
- PR #269 reviewed head SHA: `99438a210a905939554f414d7f1038ef01e3b5a2`
- Implementation branch: `fix/pr267-runtime-root-causes`
- SHA classes (F12): implementation SHA = pending owner commit after this remediation,
  PR head SHA = `99438a210a905939554f414d7f1038ef01e3b5a2`, PR merge-test SHA = N/A,
  tested SHA = pending exact-head validation, workflow run IDs = pending remote CI rerun.

Status vocabulary: **CLOSED** (implemented + proven by tests run locally), **PARTIAL** (safely-
completable slice implemented + proven; a bounded remainder is explicitly deferred/fail-closed),
**BLOCKED** (cannot be verified in this environment; owner action required).

## PR A — runtime root causes

| ID | Title | Status | Key files | Proof |
| --- | --- | --- | --- | --- |
| F01 | Idempotency-Key fails closed + BFF/Core grammar parity | CLOSED | `lib/bff/bff-idempotency-key.ts`, `shared/contracts/idempotency-key-contract.json`, `bff-route-registry.ts`, `bff-proxy.ts`, `common/api/ClientIdempotencyKey.java` | `bff-proxy-boundary.test.mjs` (8 F01 tests), `ClientIdempotencyKeyContractParityTest` (3) |
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
| F11 | Full tracked-file secret scanner | CLOSED | `scripts/check-no-secrets.ps1` | `-SelfTest` covers prod/test/e2e detection, `.env.example`, example/test-only lines, exact-match fingerprint allow, same-line wrong-literal allowlist protection, changed fixture, PEM, redaction, fail-closed unreadable files; real scan must remain clean. |
| F12 | Exact-head evidence integrity + ledger | CLOSED (this doc) | this ledger, `scripts/check-evidence-integrity.mjs`, `RELEASE_EVIDENCE_MANIFEST.md` | `check-evidence-integrity.mjs` fails on unresolved markers / PASS-without-proof; placeholder removed |
| F13 | Isolate E2E build artifacts (dev vs standalone `.next`) | CLOSED | `next.config.mjs` (validated env-driven `distDir`), `e2e/dev-server.mjs` (isolated `.next-e2e-dev`), `e2e/run-e2e.mjs` (SHA-256 artifact-manifest integrity check, deterministic pre/post cleanup, tracked-file restore), `e2e/standalone-server.mjs` (idempotent asset prep), `e2e/p1b-bff-boundary.spec.ts` (sign-in hydration-race + root-redirect fix), `.gitignore` (`.next-*/`), `eslint.config.mjs` | `npm run build` exit 0; `npm run test:e2e` 10/10 passed twice consecutively (exit 0, deterministic); the runner hashes every file under `.next` after build and re-hashes after Playwright — production artifact byte-identical across both runs (4279 files); dev writes only the isolated gitignored `.next-e2e-dev`, removed before and after every run |
| F14 | Transitive RSC import graph | CLOSED | `tests/rsc-transitive-import-guard.test.mjs`, `tests/fixtures/rsc-negative/*` | 3/3: catches deep page→component→helper→browser-api chain (prints chain), stops at `use client` / `.server.ts` boundaries, no real-app violation |
| F15 | Generated Next file cleanliness | CLOSED | `tests/worktree-cleanliness.test.mjs` (next-env.d.ts diff + canonical form + tsconfig distDir-leak guard), `e2e/run-e2e.mjs` (snapshot/restore of tracked generated sources) | Proven after full `next build` + Playwright E2E locally: `git diff --exit-code -- next-env.d.ts tsconfig.json` clean (exit 0) and the `git status --porcelain --untracked-files=all` pre-build vs post-E2E baseline comparison shows no new generated or untracked files (the dev runtime rewrites next-env.d.ts/tsconfig.json for the isolated distDir; the runner restores both deterministically). CI re-enforces the same check after build/E2E. |
| F16 | Permission/plane/read-mutation BFF/Core route parity | CLOSED (narrow); full transport-contract parity PARTIAL | `lib/bff/bff-tenant-routes.generated.json`, `tests/bff-route-artifact.test.mjs`, `BffCoreRoutePolicyParityTest.java` | Frontend drift guard + Core parity prove method/template/permission equality, tenant plane only, and read/mutation class. Full parity for content type, max body size, idempotency policy, and query contract against Core remains PARTIAL. |

## Explicitly NOT_PASS (unproven production gates — unchanged by this remediation)

- P1-C real identity provider; P1-D public Core ingress
- Live Redis topology / deployed production startup on a clean host
- Production streaming multipart intake topology (F02 remainder)
- Operant support/staff identity plane in production
- Remote CI green at the post-fix implementation SHA

## Plane separation (unchanged, re-verified by F16)

Tenant User Access, External Customer Access, Service Account Access, and the Operant Support &
Maintenance plane remain separate. F16 proves the current tenant BFF route set uses no `STAFF_*` permissions and excludes support/internal/public routes
through the tenant-operator BFF; the BFF registry is a strict, generated subset of Core policy.
