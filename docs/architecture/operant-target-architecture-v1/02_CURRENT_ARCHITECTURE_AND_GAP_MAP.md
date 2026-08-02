# 02 — CURRENT ARCHITECTURE AND GAP MAP

Scope: honest current-state statement for `OrderPilot-Core` head `b2b7255d` (main), and the current-vs-target gap map. Grounded in: execution-state v12 (S2), ARCHITECTURE_FREEZE_0 code-grounded findings at `c11f7e8` (S4 — one commit behind head; delta = FREEZE_0 docs + `Stage2Dtos.java` change, NOT_REVIEWED), stage-axis docs (S11).
Status: PARTIAL_FOR_DECLARED_SCOPE — reuses S4's code-grounded evidence; independent re-verification at head and the coverage CSV are pending (continuation manifest).

## 1. What exists (implementation evidence status per V3 §7)

### Deployables (current)
- `apps/core-api` — Java 21 / Spring Boot modular monolith, system of record. 969 Java sources (domain 439, application 331, api 130, security 45, common 18, infrastructure 6). Migrations V1…V69 (Flyway).
- `apps/web-dashboard` — Next.js BFF + dashboard; browser auth BFF_OIDC_AUTHORIZATION_CODE; Redis-required session store.
- `apps/ai-worker` — Python, advisory-only placeholder.
- `apps/operantctl` — Java 21 bounded control-plane CLI (P1-E read foundation; CODE_PROVEN by tests, production runtime NOT_PROVEN).
- PostgreSQL (single authoritative DB), Redis (session/replay/rate), object storage, Docker Compose locally; hardened Linux topology per P1-D (merged #282).

### Safety spine — IMPLEMENTED_BUT_NOT_RUNTIME_PROVEN unless noted
- Route authority: `ApiRouteSecurityPolicy` single source, global `/api/**` default-deny, fail-closed unknown/wrong-method paths. CODE_PROVEN by security tests.
- Identity planes: P1-A…P1-D CODE_PROVEN (config guards, BFF boundary, OIDC/session lifecycle, STAFF/tenant/service separation, network topology tests).
- Transactional outbox exists, external publishing disabled by construction (`PENDING → PUBLISHED_INTERNAL_ONLY | SKIPPED_EXTERNAL_DISABLED`).
- Idempotency + request integrity first-class (V55); audit append-only (V66 polymorphic principal).
- Connectors: default DRAFT + READ_ONLY; sole real executor = bounded `PROCESSING_JOB_STATUS_REPAIR` (OP-CAP-54); everything else dry-run/stub.
- Control plane P1-E read foundation: CODE_PROVEN (signed OPERANT_CONTROL_V1 protocol, bounded probes, DPAPI credential store); lifecycle commands (logs/backup/restore/upgrade/rollback) NOT_IMPLEMENTED.

### NOT_PROVEN (from S2, verbatim scope)
Production credential issuance; private management ingress; live deployed operantctl; staff SSO/MFA/JIT/ticket binding; persistent control-access audit; clean-host runtime; real IdP interop; deployed Redis failover; DNS-pinned IdP connections; clean-host Linux deploy/reboot (P1-H); public Core ingress closure on real host; independent external-customer, service-account, and staff authentication flows. Also: external connector writes, outbox external publish, real payment matching, MFA delivery.

### Business capability layer (historical Stage/OP-CAP axis, status PARTIAL overall)
Intake→RFQ→validation→operator review→draft quote→quote review/convert flows exist across Stages 1–14 + OP-CAP work (bot runtime, channel gateway, trust/AI advisory Stage 17–20, runtime entitlements Stage 16A–K, command-center read models OP-CAP-21…26). Most is "broad Core v1 medium-layer, partial or demo/local controlled, not production-complete" (canonical pointer, 2026-06-04, predates P1 work — freshness caveat).

## 2. Current structural debt (code-grounded, S4 §3–4)

| # | Debt | Evidence | Severity |
|---|---|---|---|
| G1 | Controller sprawl: 76 controllers, 6 duplicate business paths D1–D6 (webhook entry ×4+, intake/RFQ handoff ×4, validation review ×8, quote draft/review/convert ×6, analytics ×7, legacy `/api/stage8|9` beside `/api/v1`) | S4 §4 table | modularity debt, no safety break |
| G2 | Quote-god-module risk: quote surface spread over ≥6 controllers | S4 §3.3 | high (blocks clean `quote` ownership) |
| G3 | `draft_quote.status` unconstrained VARCHAR(40); Quote/Order lifecycle string-valued, no DB CHECK/enum | S4 §3.4, §12 | high (implicit state machine) |
| G4 | `workspace` domain (34 files) mixes Quote/Order/review/exception-cockpit ownership | S4 §3.5 | medium |
| G5 | `trust` domain 86 files spanning AI memory, risk decisions, analytics, projection | S4 §3.6 | medium |
| G6 | Legacy namespaces `/api/stage8/*`, `/api/stage9/*` | S4 §3.2 | medium (FE-dependent) |
| G7 | Head delta `Stage2Dtos.java` (+166 lines) after freeze baseline | git show b2b7255 | NOT_REVIEWED |

## 3. Gap map: current → target (direction shared by both rival proposals S4/S5)

Both proposals agree on: strict modular monolith + isolated workers; one authoritative owner module per business state; adapters never authority; audit+outbox atomic with mutation; at-least-once + idempotent effect (no exactly-once claim); four access planes; tenant discriminator everywhere; projections rebuildable and never truth; PostgreSQL single transactional truth; Redis never sole truth; extraction seams documented not implemented. **These invariants are treated as the stable core of the target and carried into Passes 2–4.**

They disagree on (→ OD-01, Pass 2/6 reconciliation):
- Module decomposition granularity and names (30 vs 31, different splits of identity/access, work management, finance).
- Buy-side scope now (S5 adds supplier/sourcing/procurement/AP/S2P modules; S4 defers claims-collections and leaves buy-side out).
- Technology adoption: S5 mandates RabbitMQ, Typesense, Novu, Spring Modulith+ArchUnit as law; S4 proposes enforcement scaffolding but no broker/search/notification adoption, defaulting to outbox-polling.
- Migration shape: S4 Waves 0–6 (namespace collapse, quote de-god, intake canonicalization, trust split, delivery split, projection consolidation) vs S5 Slices M1–M11 (adds RabbitMQ/Typesense/Novu foundations, marketplace pilot, controlled external write, O2C/S2P contracts, BotCreationEngine).

## 4. Immediate implications for the target package

1. Wave/slice plans must be merged into one migration architecture (file 16) mapped to P1-F/P1-G/P1-H and later P-stages — neither plan replaces P1–P9.
2. Quote/Order state enforcement (G3) is the highest-priority hardening decision in both proposals (S4 ADR-010; owner decision #6).
3. The four-plane security model and default-deny route policy are current strengths the target must preserve unchanged in shape while extending (staff SSO/MFA/JIT still NOT_PROVEN).
4. External effects remain disabled by construction until P1-F relay lands — every target design in files 07/09/11 must keep the "committed intent → bounded executor → reconciliation" gate.
