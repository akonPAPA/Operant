# Open-Source Dependency Policy

> This document is an internal policy, not formal legal advice. Consult qualified
> counsel for specific licensing questions.

## Purpose

This policy defines which open-source licenses OrderPilot may consume as dependencies,
which require review, and which are blocked. The goal is to use open-source safely
without compromising OrderPilot's proprietary, commercial SaaS position.

## Default position

OrderPilot is proprietary. Dependencies are consumed as third-party components under
their own licenses; they never relicense OrderPilot's own code. Every new dependency
must pass normal security review and license review before adoption.

## Allowed licenses

Allowed by default after normal security review, with notices preserved:

- MIT
- Apache-2.0
- BSD-2-Clause
- BSD-3-Clause
- ISC

## Review-required licenses

These require review and written sign-off before use in runtime code:

- MPL-2.0
- LGPL
- EPL
- CDDL
- SSPL-like or other source-available licenses

## Blocked licenses

Blocked unless explicitly approved in writing:

- GPL (any version)
- AGPL (any version)
- Unknown / no-license code
- Copied StackOverflow / GitHub snippets with unclear licensing
- Proprietary SDKs whose terms conflict with OrderPilot's commercial SaaS model

Avoid dependencies that impose:

- Source disclosure of OrderPilot code
- Network-use source disclosure (for example AGPL-style obligations)
- Field-of-use restrictions
- Non-commercial restrictions
- Unclear or unstated redistribution terms

## Dependency intake checklist

Every new dependency should record:

- Package name
- Version
- License
- Purpose
- Runtime or dev scope
- Risk note (where needed)

## SaaS / backend caution

OrderPilot runs as a network-facing SaaS. AGPL and similar network-copyleft licenses
are especially dangerous here because serving the software over a network can trigger
source-disclosure obligations. Treat backend, AI worker, and bot runtime dependencies
with extra care.

## Frontend / package caution

Frontend and Node package dependencies are shipped or bundled to clients and build
artifacts. Confirm that bundling and redistribution terms are compatible with a
proprietary product before adoption.

## Fork / modification rule

If a dependency is forked or modified, its original license still applies to the
forked code. Preserve the original license and notices, and review any copyleft
obligations triggered by modification or distribution.

## Notice obligations

Preserve all required attribution and license notices. Record third-party obligations
in `THIRD_PARTY_NOTICES.md`. Lockfiles do not satisfy notice obligations.

## CI / license scanning recommendation

A future, non-blocking license inventory step is recommended (see
`docs/runbooks/dependency-license-review.md`). Do not introduce a brittle CI failure
gate unless the repository already has a stable pattern for it.
