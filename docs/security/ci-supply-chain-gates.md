# CI and supply-chain gates

This document identifies which repository checks are release-blocking and which are
scheduled or advisory. A green check must not imply that a skipped gate ran.

## Blocking pull-request and main-branch gates

- `CI / Backend tests` runs the non-integration Core API test suite.
- `Backend / backend-integration-tests` runs PostgreSQL integration tests.
- `CI / Core API release Docker guard` validates the Dockerfile chain and builds the
  final release image. The image build runs the Docker `verify` stage before the
  `package` stage; `-DskipTests` is allowed only in `package` after verification.
- `Frontend / build` uses the committed npm lockfile through `npm ci`, then runs lint,
  typecheck, build, and tests when `apps/web-dashboard/**` changes.
- `AI Worker / test` installs the declared Python development dependencies and runs
  pytest when `apps/ai-worker/**` changes.
- `Semgrep Security Scan` blocks on ERROR-severity registry and local policy findings.
  WARNING/INFO findings run in a separately named advisory step.

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
