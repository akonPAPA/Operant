# Third-Party Notices

## Purpose

OrderPilot is proprietary software that depends on third-party open-source components.
This document records how OrderPilot treats third-party dependency licenses, which
license classes are acceptable, and how their notices are preserved. It does not
relicense OrderPilot itself: the OrderPilot codebase remains proprietary (see
`LICENSE` and `NOTICE`).

## Dependency ownership

Each third-party dependency remains the property of its respective authors and is
governed solely by its own license. Including a dependency in OrderPilot does not
transfer ownership of that dependency to OrderPilot, and does not place OrderPilot's
own proprietary code under that dependency's license.

## Allowed dependency license classes

The following permissive licenses are generally allowed for normal application and
build dependencies, provided their copyright and license notices are preserved:

- MIT
- Apache-2.0
- BSD-2-Clause
- BSD-3-Clause
- ISC

## Licenses requiring review

The following licenses require review and approval before use in core runtime code
(backend, frontend, AI worker, bot runtime, integrations):

- MPL-2.0
- LGPL (any version)
- EPL (any version)

Weak-copyleft and file-level-copyleft terms can be acceptable in limited cases, but
the linkage model and distribution/network-use implications must be reviewed first.

## Prohibited without legal approval

The following are prohibited in core runtime, backend, frontend, and AI worker code
unless explicitly approved in writing:

- GPL (any version)
- AGPL (any version) — especially prohibited for SaaS / network-facing runtime use,
  because network interaction can trigger source-disclosure obligations
- Code with no license, or with unclear/unknown licensing
- Code copied from unknown GitHub repositories, gists, or forums without verifying
  the license

Additional rules:

- Do not copy code from unknown GitHub repositories into OrderPilot without verifying
  the license.
- Do not remove or alter copyright notices from third-party code.
- Generated lockfiles (for example `package-lock.json`, `poetry.lock`, Maven
  resolution output) are not license notices and do not replace license review.

## How to update notices

When a dependency is added, changed, or removed:

1. Record the package name, version, and license.
2. Confirm the license falls in an allowed class, or route it through review.
3. Preserve any required attribution or notice text from the dependency.
4. Update this file and `docs/legal/OPEN_SOURCE_POLICY.md` if policy guidance changes.

See `docs/runbooks/dependency-license-review.md` for the operational procedure.

## Current status

Initial baseline created. A full automated dependency license inventory still needs
to be generated.
