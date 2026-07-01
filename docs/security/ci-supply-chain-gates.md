# CI and supply-chain gates

This document identifies which repository checks are release-blocking and which are
scheduled or advisory. A green check must not imply that a skipped gate ran.

## Blocking pull-request and main-branch gates

- `CI / Backend tests` runs the non-integration Core API test suite.
- `Backend / backend-integration-tests` runs PostgreSQL integration tests.
- `CI / Core API release Docker guard` validates the Dockerfile chain and builds the
  final release image. The image build runs the Docker `verify` stage before the
  `package` stage; `-DskipTests` is allowed only in `package` after verification.
- `Frontend / build` always runs and reaches a terminal success/failure conclusion so
  the required check is never reported as skipped/neutral. A `changes` job detects
  frontend edits; when `apps/web-dashboard/**` (or the frontend workflow) changes, the
  `build` job runs `npm ci`, lint, typecheck, build, and tests against the committed
  lockfile. When there are no frontend changes it satisfies the required check with a
  no-op step instead of being skipped.
- `AI Worker / test` always runs and reaches a terminal success/failure conclusion so
  the required check is never reported as skipped/neutral. A `changes` job detects AI
  Worker edits; when `apps/ai-worker/**` (or the AI Worker workflow) changes, the
  `test` job installs the declared Python development dependencies and runs pytest.
  When there are no AI Worker changes it satisfies the required check with a no-op step
  instead of being skipped.
- `Semgrep Security Scan` blocks on ERROR-severity registry and local policy findings.
  WARNING/INFO findings run in a separately named advisory step.

### Why required checks must not be skipped/neutral

Branch protection treats a required check that is skipped or stays neutral as an
unsatisfied requirement, which can leave PRs blocked or "pending" indefinitely even
when nothing relevant changed. The `Frontend / build` and `AI Worker / test` jobs
therefore keep their path-aware `changes` job but never put a job-level `if:` on the
required job. Instead they always start the required job and gate the heavy work at the
step level, so the job id/name (`Frontend / build`, `AI Worker / test`) stays stable
for branch protection and always concludes success or failure.

## Code scanning (CodeQL)

CodeQL is managed by **GitHub Code Scanning Default Setup**, not by an in-repository
workflow. The advanced/generated CodeQL workflow (`.github/workflows/codeql.yml` and
`.github/codeql/codeql-config.yml`) was intentionally removed once Default Setup was
enabled, because GitHub does not allow Default Setup and an advanced CodeQL workflow to
coexist for the same language.

Implications:

- There is no `codeql.yml` in this repository on purpose. Do not add one while Default
  Setup is enabled; a duplicate would fail with a "default setup is enabled" error.
- If branch protection requires a CodeQL/code-scanning check, that check is produced by
  Default Setup and must be enabled in repository settings so it runs on pull requests
  against `main`. Verify this under Settings → Code security → Code scanning.
- If Default Setup cannot provide a stable required check on PRs, disable Default Setup
  first, then add an explicit CodeQL workflow (languages: Java, JavaScript/TypeScript,
  and Python if needed) in a dedicated PR. Do not do both at once.

## Dependabot PR validation policy

- Major frontend tooling bumps (for example ESLint) must pass the full `Frontend / build`
  gate: lint, typecheck, build, and tests. A green diff is not sufficient.
- A failing major bump must be closed or ignored (Dependabot `ignore`), not merged by
  weakening a gate. For example, ESLint `9.x -> 10.x` on PR #221 fails only Frontend
  lint; it must not be merged until frontend lint is compatible, or it must be closed.
- No workflow may be weakened (removed steps, `continue-on-error: true`, downgraded
  severity, or job-level `if:` that skips a required check) to make a dependency bump
  pass. Required-check normalization and dependency correctness are independent.

## Scheduled/manual dependency gate

Snyk is intentionally scheduled/manual while the existing dependency backlog is
remediated. Each invocation fails closed when `SNYK_TOKEN` is missing and blocks on
high/critical findings. It is not currently a pull-request or release gate.

Dependabot discovers manifests from their containing directories and proposes weekly
Maven, npm, and Python dependency updates. Node builds must use `npm ci` whenever a
lockfile is present; CI and Docker must not rewrite the lockfile.

## Action update policy

GitHub-authored actions use reviewed major-version tags rather than immutable commit
SHAs. This is an explicit maintainability trade-off. Updates must be reviewed through
normal pull requests, token permissions remain least-privilege, and checkout steps do
not persist credentials. Broad SHA pinning is deferred to a dedicated migration.
