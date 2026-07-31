# Operant Dependency Rules (enforcement design — NOT implemented in this freeze)

Source commit `c11f7e8`. These are the enforceable rules the target architecture must pass. They are
**design specifications** for Spring Modulith + ArchUnit checks; no check is added by this freeze.

## Module structure

```
<module>/
├── api/            # facade, commands, queries, results, published-events  (importable by others)
└── internal/       # application, domain, persistence, web, provider-adapters, mapping (private)
```

## Rules (each becomes one test)

1. **Public-API-only dependency.** Other modules may import only `<module>.api`; importing
   `<module>.internal.*` fails. (ArchUnit: no access to `..internal..` across module roots.)
2. **No cross-module repository access.** A repository type may be referenced only within its owning
   module. (ArchUnit: `..persistence..Repository` classes accessed only from same module.)
3. **No cross-module entity mutation.** JPA `@Entity` types are not passed across module boundaries and not
   mutated outside the owner. (ArchUnit: `@Entity` classes reside in and are written only by owner.)
4. **No domain → application dependency.** `..domain..` must not depend on `..application..`.
5. **No application → web/controller DTO dependency.** `..application..` must not depend on `..api.rest..`
   request/response DTOs; commands are separate types.
6. **No global API DTO used as an application command.** Public request DTOs never reach services directly
   (preserves OP-CAP-31 `public DTO → resolver → clean command → service`).
7. **No JPA entity exposed as a module contract.** `api` packages contain no `@Entity`.
8. **No cyclic module dependency.** (Spring Modulith `ApplicationModules.verify()` / ArchUnit slices cycle-free.)
9. **Route/permission parity.** Every controller mapping has exactly one `ApiRouteSecurityPolicy`
   classification; unclassified/write-shaped internal routes fail closed. (Extend existing
   `ApiRouteSecurityClassificationTest`.)
10. **Tenant ownership.** Every business repository query is tenant-scoped. (ArchUnit + naming/lint.)
11. **Direct-entity-response prevention.** Controllers return DTOs, never `@Entity`.
12. **DTO authority-field checks.** Request DTOs carry no tenant/actor/role/status/approval/price authority;
    response DTOs expose no secret/internal-id/audit/outbox/payload internals. (Contract tests, already a
    pattern — e.g. `IncidentBreakGlassContractTest`, `Stage9IntegrationControllerTest`.)
13. **Audit/outbox transaction rules.** `AuditEvent`/`OutboxEvent` appended only inside a business tx via
    the audit/outbox service; no independent commit path.
14. **Idempotency & concurrency rules.** Mutating endpoints accept `Idempotency-Key`; state transitions use
    the smallest sufficient concurrency mechanism (ADR-010).

## Existing footholds to build on

- `LifecycleTerminalOwnerArchitectureTest` — architecture test precedent.
- `ApiRouteSecurityClassificationTest` — route inventory / classification parity.
- Contract tests asserting no-leak responses (multiple stages).
