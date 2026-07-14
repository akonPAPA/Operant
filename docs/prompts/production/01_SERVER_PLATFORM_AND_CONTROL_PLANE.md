# Controller Prompt 01 — Server Platform and Control Plane

**Master:** [`/OPERANT_PRODUCTION_MASTER_PROMPT.md`](../../OPERANT_PRODUCTION_MASTER_PROMPT.md)
**Phase:** 1 of 3
**Anchor base SHA:** `7f05a1751d04d22ef572d8d6aca0dcbdc457df72` (`main`, PR #262)

## Objective

Establish a reproducible Linux-hosted production platform: Browser → BFF → Core trust boundary, production identity/session seam, authenticated `operantctl`, and outbound-only cross-platform agent foundation.

## Bounded PR sequence (Phase 1)

| Slice | Gate focus |
| --- | --- |
| P1-A | Truth and configuration |
| P1-B | Browser/BFF boundary |
| P1-C | Production identity (OIDC mapping) |
| P1-D | Linux deployment |
| P1-E | operantctl |
| P1-F | Connector Gateway protocol |
| P1-G | operant-agent |
| P1-H | Recovery and observability |

## P1-A scope (this slice)

- Typed production configuration validation
- Reject demo authority in production-like profiles
- Reject unsigned/disabled gateway trust in production-like profiles
- Reject placeholder secrets
- Validate required production URLs, ports, timeouts, limits
- Redacted configuration diagnostics
- Focused tests + security/configuration regression suite

**Out of scope for P1-A:** BFF, OIDC implementation, operantctl, operant-agent, Connector Gateway, Controller 02.

## Phase acceptance gates

See master prompt §Phase acceptance gates (`P1-GATE-01` … `P1-GATE-13`). No gate PASS without commit-linked evidence in [`RELEASE_EVIDENCE_MANIFEST.md`](../production/RELEASE_EVIDENCE_MANIFEST.md).

## Mutable state

- [`/OPERANT_PRODUCTION_EXECUTION_STATE.md`](../../OPERANT_PRODUCTION_EXECUTION_STATE.md)
- [`docs/production/PRODUCTION_READINESS_MATRIX.md`](../production/PRODUCTION_READINESS_MATRIX.md)
- [`docs/production/TRUST_BOUNDARY_MATRIX.md`](../production/TRUST_BOUNDARY_MATRIX.md)
- [`docs/production/DATA_AUTHORITY_MATRIX.md`](../production/DATA_AUTHORITY_MATRIX.md)

Do not start Controller 02 until every Phase 1 gate is PASS at a compatible commit.
