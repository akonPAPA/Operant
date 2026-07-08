# Snyk baseline policy (OrderPilot / Operant)

## Purpose

Define an honest policy for how OrderPilot uses Snyk in CI without pretending the repository’s existing dependency baseline is already clean.

This document exists because the repository added a **skip-safe Snyk PR gate** (PR #261) that enforces Snyk only when dependency manifests change. That is intentional and safe, but it does **not** remediate the existing baseline automatically.

## What is true today (honest state)

- Snyk dependency scanning exists as a GitHub Action workflow: `.github/workflows/snyk.yml`.
- The PR gate **enforces only when dependency manifests/lockfiles change**, not on workflow-only PRs.
- The current **core-api high/critical baseline is not proven clean** inside this repository state unless a Snyk run output is attached as evidence.
- `Snyk Dependency Scan / Snyk Gate` must **not** be added as a required GitHub ruleset check until the baseline is clean or an explicit baseline acceptance policy is approved.
- AI Worker Python dependencies are **not** claimed as Snyk-covered by the current workflow (explicitly documented as a residual in the workflow).

## Why “required check” is gated on baseline readiness

Making `Snyk Dependency Scan / Snyk Gate` required before the baseline is clean (or explicitly baselined) forces every PR to become a dependency-remediation PR, which:

- creates merge deadlocks for docs/proof PRs that must not change dependencies,
- encourages unsafe “upgrade everything” changes to satisfy the gate,
- and invites dishonesty (hiding findings instead of remediating them).

## Current CI enforcement contract (PR #261)

The workflow is intentionally split into:

- **Dependency relevance**: when a PR changes dependency manifests/lockfiles for:
  - `apps/core-api/**/pom.xml`
  - `apps/web-dashboard/package.json`, `apps/web-dashboard/package-lock.json`
- **Workflow relevance**: when a PR changes `.github/workflows/snyk.yml`

When only the workflow changes and dependency manifests do not, the workflow states it is **not applicable** as a dependency enforcement in that PR, while scheduled/manual runs remain baseline sweeps.

Fork PR behavior: if dependency manifests change on a fork PR, Snyk fails closed because secrets are unavailable.

## Baseline readiness exit criteria

`Snyk Dependency Scan / Snyk Gate` may be added to the GitHub ruleset required checks only when at least one of the following is true:

1. **Baseline remediation complete**:
   - A Snyk scan of `apps/core-api/pom.xml` reports **0 high/critical** findings, and
   - A Snyk scan of `apps/web-dashboard` reports **0 high/critical** findings, and
   - Evidence is attached in a PR as command output or CI logs.

2. **Explicit baseline acceptance policy approved**:
   - A baseline allowlist/ignore strategy is documented and accepted (with owner approval),
   - with explicit time-bounded follow-ups for each accepted high/critical.

Until then, Snyk remains a best-effort PR gate on dependency changes plus scheduled baseline reporting.

## Follow-up required (if baseline is not clean)

If Snyk high/critical findings exist and cannot be remediated safely in a bounded PR, create a dedicated follow-up PR (example name):

- `security/dependency-baseline-core-api-snyk`

That follow-up must include:

- finding ID + dependency + current version + fixed version,
- direct vs transitive path,
- upgrade risk and compatibility notes (Java 21 / Spring Boot),
- targeted tests required to prove safety.

