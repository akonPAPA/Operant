# ADR-003 — Module data ownership

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** No formal per-module table ownership; cross-module repository access is technically possible.
- **Current evidence:** `data-authority-model.md` (backend owns mutations, tenant isolation mandatory);
  tenant-scoped repositories are the norm.
- **Options:** (a) shared free-for-all repositories; (b) one authoritative owner module per business state,
  cross-module access only via public API (IDs/value objects/commands/queries/results/events).
- **Decision:** (b). See `OPERANT_DATA_OWNERSHIP_MATRIX.tsv`. One writer per state; no cross-module entity
  mutation; audit/outbox commit atomically with the owning mutation.
- **Consequences:** clear ownership; enforceable via ArchUnit (no cross-module repo imports).
- **Rejected alternatives:** shared repositories (ambiguous ownership, quality-gate failure: multiple
  writers of one state).
- **Migration impact:** repackage repositories under owning modules (Waves 2/4).
- **Security impact:** positive — tenant scope centralized per owner.
- **Performance impact:** neutral; hot-path queries stay in owner module with proper indexes.
- **Reversibility:** high.
- **NOT_PROVEN:** exact table→module assignment for a few multi-concern packages (trust/workspace) pending
  the split.
