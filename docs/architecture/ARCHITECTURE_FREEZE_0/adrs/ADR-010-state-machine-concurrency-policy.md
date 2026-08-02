# ADR-010 — State-machine and concurrency policy

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** Some critical states are string-valued without DB enforcement; concurrency strategy must be
  the smallest sufficient mechanism per invariant.
- **Current evidence:** `draft_quote.status` is unconstrained `VARCHAR(40)` (no CHECK/enum/allowlist —
  checkpoint OP-CAP-36 closure); pessimistic locks already used (channel-identity, quote assemble); optimistic
  versions elsewhere; `WorkerJobLeaseService` and control executor leases exist.
- **Options:** (a) keep implicit string states; (b) declare enforceable state machines + a concurrency ladder.
- **Decision:** (b). Every critical aggregate has a catalogued state machine (`OPERANT_STATE_MACHINE_CATALOG.tsv`).
  Concurrency ladder (smallest first): DB constraint/atomic conditional → `@Version` → pessimistic lock →
  CAS → serialized queue owner → lease+fencing → Saga (last resort, none needed internally). Quote/Order
  status gets a DB CHECK or JPA enum (owner decision on mechanism).
- **Consequences:** illegal transitions become impossible at the DB/domain layer; predictable concurrency.
- **Rejected alternatives:** implicit string states (silent illegal transitions); distributed locks/Saga
  where a local mechanism suffices (quality-gate failure).
- **Migration impact:** Wave 2 adds `quote_status` enforcement (additive migration — outside this freeze).
- **Security impact:** positive — forbidden transitions blocked deterministically.
- **Performance impact:** positive — minimal locking; hot paths use conditional updates.
- **Reversibility:** medium (adding a CHECK constraint is reversible).
- **NOT_PROVEN:** Quote/Order transition set inferred from services, not a declared enum (CURRENT_PARTIAL).
