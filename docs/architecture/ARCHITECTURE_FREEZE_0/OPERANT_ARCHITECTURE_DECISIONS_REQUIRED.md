# Operant Architecture — Owner Decisions Required

Source commit `c11f7e8`. These block declaring the freeze **final**. Ordered by priority.

| # | Decision | Why it is an owner decision | Options | Default recommendation | Blocks |
|---|---|---|---|---|---|
| 1 | **State-1/2/3 ↔ Phase-1 mapping** | `OPERANT_THREE_STATE_EXECUTION_ROADMAP_V2.md` (the doc defining "State 2") is **absent from the repo**; the in-repo axis is Phase 1 (P1-A..H). "State-2-compatible" cannot be authoritatively confirmed without it. | (a) accept the mapping in master §1.1; (b) restore/point to the roadmap doc | (a) with owner sign-off; then re-label freeze final | Final verdict |
| 2 | **billingar / Invoice-AR ownership** | Only `payment` obligation intelligence exists; no invoice aggregate. Is AR an owned transactional aggregate or an ERP-mirror projection? | (a) owned `billingar` aggregate; (b) ERP-mirror projection under `commerceintelligence` | (b) mirror until ERP write is proven | billingar module status |
| 3 | **securitydelivery split + MFA provider** | MFA/security messaging must be separate from business messaging (ADR-008); no MFA delivery exists today. | (a) separate module + provider now; (b) defer to post-P1-F | (a) module boundary now, provider later | Delivery design |
| 4 | **Redis physical split** | Two operational roles justified (ADR-005) but physical split has an ops-budget cost. | (a) two Redis deployments; (b) one Redis, two logical namespaces | (b) now, (a) at scale | Redis topology |
| 5 | **Legacy `/api/stage8` `/api/stage9` deprecation timeline** | Collapsing onto `/api/v1` depends on frontend migration schedule. | (a) alias + deprecate in Wave 1; (b) keep indefinitely | (a) | Wave 1 |
| 6 | **Quote/Order state enforcement mechanism** | `draft_quote.status` is unconstrained VARCHAR (ADR-010). | (a) DB CHECK; (b) JPA enum + converter; (c) central allowlist | (a)+(b) | Wave 2 migration |
| 7 | **Connector Gateway (P1-F) protocol boundary** | Determines whether `connectorexecution` is an in-monolith module or the first extraction seam. | (a) in-monolith module fronted by gateway; (b) extracted service | (a) until gate (ADR-012) satisfied | connectorexecution status |
| 8 | **Staff SSO/MFA/JIT identity** | Staff plane is a dedicated machine credential today; production staff SSO/MFA/JIT is NOT_PROVEN. | (a) staff IdP now; (b) after P1-H | (b) | identityaccess completeness |
