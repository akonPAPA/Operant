# Dependency License Review Runbook

This runbook explains how to review the license of a new or updated dependency before
it is adopted in OrderPilot. It complements `docs/legal/OPEN_SOURCE_POLICY.md` and
`THIRD_PARTY_NOTICES.md`.

## How to review a new dependency license

1. Identify the dependency: name, version, and scope (runtime or dev).
2. Determine its license from the package metadata and the project's source repository.
3. Classify the license against `docs/legal/OPEN_SOURCE_POLICY.md`:
   - Allowed by default: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC.
   - Review required: MPL-2.0, LGPL, EPL, CDDL, SSPL-like / source-available.
   - Blocked unless written approval: GPL, AGPL, unknown/no-license code.
4. Record name, version, license, purpose, scope, and a risk note where needed.
5. Preserve any required attribution/notice text.

## How to reject GPL / AGPL / no-license dependencies

- Reject GPL and AGPL dependencies in core runtime (backend, frontend, AI worker, bot
  runtime, integrations) unless there is explicit written approval. AGPL is especially
  unsuitable for network-facing SaaS.
- Reject dependencies with no license or unclear licensing.
- Reject copied snippets from forums or unknown repositories with unverified licensing.
- When rejecting, prefer a permissively licensed alternative, or escalate for written
  approval if the dependency is essential.

## Suggested future automation

These are recommendations, not a mandated CI gate:

- Frontend: an npm license checker (for example a `license-checker`-style tool) over
  `apps/web-dashboard`.
- Java: a Maven license plugin or dependency inventory for `apps/core-api`.
- Python: `pip-licenses` or an equivalent for `apps/ai-worker`.
- GitHub Dependabot / security review for ongoing dependency monitoring.

## Caution

Do not implement a brittle CI failure gate unless the repository already has a stable
pattern for it. A non-blocking inventory/report is preferred as a first step.
