# OrderPilot Agent Instructions

## Authoritative Roadmap

`ORDERPILOT_CORE_V1_AI_DEV.md` at the repository root is the authoritative roadmap and instruction file. Read it before planning or editing, and keep these agent instructions aligned with it.

## Product Identity

OrderPilot is an investor-grade B2B SaaS transaction intelligence platform for auto and industrial parts distributors. It turns messy inbound demand, customer messages, documents, pricing context, product catalog data, inventory signals, and approval workflows into controlled quote and order operations.

The system is built around trust, tenant isolation, deterministic validation, auditability, and controlled write paths. Future AI-agent, chatbot, frontend, connector, and automation work must preserve those properties.

## Non-Negotiable Architecture Rules

- AI, chatbot, frontend, connector, and worker code must never directly write to master business data.
- All business mutations must go through typed backend services with authentication, tenant policy checks, deterministic validation, database transactions, audit events, and approval gates where required.
- Preserve tenant isolation on every read, write, background job, webhook, import, export, and test fixture.
- Preserve audit-first behavior. Business decisions, external commands, approval outcomes, validation failures, and important state transitions must remain explainable from durable audit records.
- External writes and risky system changes must preserve the ChangeRequest and approval model. Do not bypass operator approval to make integrations, bots, or AI appear more autonomous.
- Treat the Core API as the system of record for business truth. UI and AI layers may propose, draft, classify, extract, or explain; they do not own final authority over master data.
- Do not weaken production security, tenant policy, validation, transactions, approval gates, or audit behavior to make tests, demos, or local workflows easier.
- Do not create duplicate architecture, tables, or parallel service paths when equivalent models or services already exist.
- Do not add AI-to-database, bot-to-database, frontend-to-master-data, or connector-to-master-data direct write paths.
- All risky actions require deterministic validation, tenant policy, approval where required, and audit.

## Development Rules

- Documentation-only tasks must not modify production Java code, tests, or `pom.xml`.
- Keep changes small and focused. Avoid opportunistic refactors.
- Before editing, inspect the relevant `AGENTS.md` files and nearby docs or code.
- Before each task, inspect existing code and reuse existing models, repositories, controllers, and services wherever possible.
- Check the existing git diff before and after edits so unrelated user changes are not overwritten.
- Use existing project patterns, package boundaries, naming conventions, and service layers.
- For Spring Boot 3 code, use `jakarta.*`, not `javax.*`. Do not add legacy `javax.servlet-api` to fix imports.

## Test Rules

- Do not disable tests.
- Do not use `skipTests` or `maven.test.skip`.
- When Maven tests fail, inspect Surefire reports first before changing code:

```powershell
Get-ChildItem -Recurse apps/core-api/target/surefire-reports
Get-Content apps/core-api/target/surefire-reports/*.txt
```

- For `@WebMvcTest` slice issues, use narrow test-scope configuration under `src/test/java`, targeted mocks, or test-only permission configuration when the test is not about permission behavior.
- Do not weaken production security to fix tests.
- Run focused tests before full-suite verification when changing backend behavior.

## Maven Verification

Run Maven from `apps/core-api` unless a task says otherwise.

Preferred focused commands:

```powershell
mvn -Dtest=SpecificTest test
mvn -Dtest=SpecificTest#specificMethod test
```

Preferred full verification:

```powershell
mvn clean test
```

Never run Maven with:

```powershell
mvn -DskipTests ...
mvn -Dmaven.test.skip=true ...
```

## Required Output Format For Future AI-Agent Tasks

Future AI agents should close implementation tasks with:

- Assumptions
- Changed files
- Tests run
- Risks/blockers
- Next recommended step

# AGENTS.md — OrderPilot Engineering Contract

**Project:** OrderPilot  
**Product type:** B2B SaaS transaction intelligence platform  
**Primary backend:** Java 21 + Spring Boot  
**Primary frontend:** Next.js + TypeScript  
**Primary database:** PostgreSQL  
**AI worker:** Python only for OCR/LLM/extraction tasks  
**Purpose:** Permanent engineering instructions for Codex, Claude Code, ChatGPT agents, and human developers working inside this repository.

---

## 0. Core rule

Do not treat OrderPilot as a toy demo, CRUD scaffold, chatbot experiment, or generic OCR app.

OrderPilot is a secure B2B SaaS platform that turns messy customer requests from email, PDF, Excel, Telegram/WhatsApp-ready channels and APIs into validated RFQs, draft quotes, draft orders, substitution suggestions, review cases, audit records, and commerce intelligence.

All code must be production-leaning, tenant-safe, auditable, and consistent with the existing architecture.

---

## 1. Non-negotiable product boundaries

OrderPilot is:

- a secure transaction intelligence layer;
- a controlled workflow system for RFQ/order intake;
- a human-in-the-loop review platform;
- a validation, substitution, pricing, stock, margin and audit system;
- a B2B SaaS core for distributors.

OrderPilot is not:

- a generic chatbot;
- a simple OCR toy;
- an ERP replacement;
- an accounting/tax engine;
- a warehouse management system;
- an autonomous AI agent that mutates trusted business systems;
- a random dashboard with fake visibility-only screens.

The main product flow is:

```text
messy customer request
  -> channel/document intake
  -> AI-assisted extraction/classification
  -> deterministic validation
  -> risk decision
  -> human review if needed
  -> draft quote/order
  -> audit event
  -> optional approved external connector command
  -> analytics/read model
```

---

## 2. AI, bot and frontend write policy

AI, chatbot and frontend are never authoritative.

### Forbidden

```text
AI worker -> direct write to order/quote/inventory/customer/price tables
Bot runtime -> direct DB mutation
Frontend -> direct DB access
Connector -> unrestricted DB write
LLM output -> final ERP/1C/accounting payload without validation
Controller -> repository direct mutation without command service
```

### Allowed

```text
Frontend / Bot / AI Worker / Connector
  -> Core API command/query endpoint
  -> Authentication
  -> Authorization: RBAC/ABAC
  -> Tenant policy
  -> Input validation
  -> Deterministic business validation
  -> Risk decision
  -> Approval gate if required
  -> Transaction service
  -> AuditEvent
  -> OutboxEvent if integration/event is needed
  -> External connector command only if approved and policy allows
```

### AI can do

- classify intent;
- extract fields;
- normalize text into suggestions;
- rank candidate products/substitutes;
- summarize evidence for the operator;
- generate confidence signals.

### AI must not do

- approve quote/order;
- approve discount;
- approve substitute;
- update inventory;
- update price;
- update customer master data;
- write to ERP/1C/accounting;
- execute arbitrary tools or SQL;
- override backend policy.

---

## 3. Default architecture style

Use a modular monolith first.

Do not introduce microservices, new frameworks, new queues, new databases, or broad architectural rewrites unless the task explicitly requires it and the reason is documented.

Main backend layers:

```text
api/rest
  -> DTO validation
  -> application command/query service
  -> domain service / policy / validator
  -> repository / query repository / adapter
  -> transaction boundary
  -> audit/outbox
  -> response DTO / read model
```

### Mutation flow

For any endpoint that changes state:

```text
REST Controller
  -> Command Request DTO
  -> Auth/Tenant context
  -> Command Service
  -> Policy check
  -> Deterministic validation
  -> Domain operation
  -> Repository save
  -> AuditEvent append
  -> OutboxEvent append if needed
  -> Response DTO
```

Rules:

- Controller must not contain business logic.
- Controller must not call repositories directly.
- Controller must not return JPA entities.
- Mutation must have an explicit command/service boundary.
- Mutation must be tenant-scoped.
- Important mutation must emit an audit event.
- External write must use ChangeRequest unless task explicitly defines safe internal-only behavior.

### Read-only query flow

For list/detail/read-only APIs:

```text
REST Controller
  -> Filter/Query DTO
  -> Tenant/Auth context
  -> Query Service
  -> Query Repository / Projection / Read Model
  -> Sanitized Response DTO
```

Rules:

- Read APIs must not mutate business state.
- Use projections/read models when returning operator/dashboard data.
- Do not expose raw unsafe payloads, secrets, tokens, connector credentials, raw customer message bodies, or AI prompt internals unless an explicitly sanitized DTO already exists.
- Use pagination for lists.
- Avoid N+1 queries.
- Avoid unbounded result sets.

---

## 4. Spring Boot backend standards

Use existing package conventions first. If a new package is needed, place it under the closest existing module.

Preferred backend shape:

```text
apps/core-api/src/main/java/com/orderpilot/
  api/rest/...              # controllers and REST DTO boundaries
  application/services/...  # command/query orchestration
  application/policies/...  # authorization/business policies
  application/queries/...   # read/query services if repo uses this convention
  domain/...                # domain model, domain rules, value objects
  infrastructure/...        # persistence, adapters, external systems
  common/...                # shared errors, tenant, security, idempotency
```

### Controllers

Controllers should:

- bind request DTOs;
- validate input;
- obtain tenant/auth context using existing mechanisms;
- call exactly the appropriate command/query service;
- map service result to response DTO;
- return structured errors through existing error handling.

Controllers should not:

- contain business rules;
- directly use repositories;
- directly create JPA entities for persistence;
- perform complex mapping logic;
- run AI or connector calls inline;
- parse large files in request thread.

### Services

Application services should:

- orchestrate the control flow;
- define transaction boundaries;
- call policies/validators/domain services;
- call repositories/adapters;
- emit audit/outbox events;
- produce DTO/read-model results.

Application services should not:

- become god classes;
- duplicate domain rules across modules;
- hide tenant checks;
- swallow exceptions silently.

### Domain services / validators / policies

Use these for:

- SKU/product validation;
- UOM validation;
- inventory checks;
- price/discount/margin checks;
- substitution risk;
- approval requirement decision;
- tenant policy decisions;
- bot policy decisions.

### Repositories

Repositories should:

- use tenant-aware queries;
- have indexes considered for hot paths;
- avoid unbounded scans;
- return domain objects or projections as appropriate.

Repositories should not:

- be called from controllers;
- expose cross-tenant data;
- implement business policy decisions.

### DTOs and entities

- Do not expose JPA entities as REST responses.
- Use request DTOs for input.
- Use response DTOs/read models for output.
- Use explicit mappers where repo convention requires it.
- Do not place sensitive fields in response DTOs.
- Use clear names that describe business meaning.

---

## 5. Tenant, auth and access control

Tenant isolation is mandatory.

Every tenant-owned row must have `tenant_id` unless the table is explicitly global/system-level.

Every API that reads or writes tenant-owned data must enforce tenant scope.

Use existing tenant context and security conventions. Do not invent parallel auth.

Required access rules:

- Tenant A must never read Tenant B data.
- Tenant A must never mutate Tenant B data.
- Bot requests must be tenant-scoped.
- AI worker jobs must be tenant-scoped.
- Integration connections must be tenant-scoped.
- Dashboard queries must be tenant-scoped.
- Admin-only operations must check role/permission.

Use RBAC/ABAC pattern where applicable:

```text
RBAC: role allows action type
ABAC: tenant/location/customer/risk/context allows specific resource action
```

---

## 6. Audit and outbox rules

Audit is product value, not decoration.

Create or preserve audit events for:

- business mutations;
- approval decisions;
- quote/order draft creation;
- validation/risk decisions when already part of the existing flow;
- connector sync/write attempts;
- bot handoff;
- security-relevant changes;
- import activation/rejection;
- status changes that matter to operators.

Audit event should answer:

```text
who did it?
what happened?
which tenant?
which entity?
when?
why/reason code if available?
what changed safely?
which external execution mode?
```

Do not put secrets, raw payloads, tokens, passwords, full prompt text, or unsafe customer data into audit metadata.

Use transactional outbox for integration/event workflows where existing architecture supports it.

---

## 7. Integration and external write policy

External systems include:

- ERP;
- 1C;
- accounting;
- warehouse system;
- customer database;
- connector agents;
- demo ERP adapter.

Default connector mode is read-only.

External writes require:

```text
internal draft/state
  -> deterministic validation
  -> approval if required
  -> ChangeRequest
  -> connector mapping
  -> idempotency key
  -> connector command
  -> external reference saved
  -> audit event
```

Do not create direct ERP/1C writes inside unrelated slices.

Do not let UI/bot/AI trigger connector writes unless the task explicitly implements the approved ChangeRequest flow.

---

## 8. Frontend architecture standards

Use existing Next.js/TypeScript conventions.

Frontend must call backend REST APIs through the existing API client/fetch abstraction.

Frontend must not:

- call the database;
- duplicate backend business rules;
- create fake state that looks real;
- log full sensitive API responses;
- store sensitive data in localStorage/sessionStorage;
- use `dangerouslySetInnerHTML`;
- display unsafe raw payloads;
- create mutation buttons in read-only screens.

Frontend should:

- use typed API DTOs or generated clients if available;
- separate API/data fetching from presentation if repo convention does that;
- have clear loading, empty and error states;
- keep operator workflows scannable;
- render safe metadata as text/key-value rows, not raw HTML;
- use existing UI components and styling;
- avoid new UI frameworks unless explicitly requested.

For operator cockpit screens, answer:

```text
what happened?
why did it happen?
what data did backend validate?
what is risky?
what is safe to do next?
who/what/when is in audit?
```

---

## 9. File upload and inbound channel policy

Inbound files/messages are untrusted.

For file upload/channel intake:

- validate size;
- validate MIME/type where supported;
- do not parse large files in the API request thread;
- store raw files/payload pointers safely;
- create processing job asynchronously;
- use idempotency/fingerprint/dedup;
- verify webhook signatures where available;
- use replay protection where supported;
- rate-limit inbound endpoints;
- audit inbound event creation;
- show failed processing state.

Do not execute Excel macros or embedded scripts.

Do not trust customer text that tries to override system instructions.

---

## 10. Error handling standards

Use existing structured error format.

Errors should be:

- controlled;
- typed where possible;
- safe for API response;
- useful for operator/developer;
- not leaking secrets, stack traces, SQL, tokens, connector credentials, or raw payloads.

Business validation failures are not the same as system bugs.

Use domain/business exceptions for expected business-rule failures where the codebase already uses that pattern.

Do not hide root causes in generic `RuntimeException`.

---

## 11. Performance and hot-path rules

Design hot paths early.

Hot paths:

- inbound webhook processing;
- SKU lookup;
- alias/OEM lookup;
- product candidate matching;
- customer identification;
- price rule lookup;
- inventory check;
- margin check;
- substitute ranking;
- quote/order draft creation;
- dashboard aggregation;
- reconciliation.

Rules:

- Use indexed queries for tenant + business key lookups.
- Use pagination/cursor patterns for lists.
- Avoid unbounded scans.
- Avoid N+1 queries.
- Do not recompute dashboard analytics from raw tables on every request.
- Use read models/materialized views where appropriate.
- Keep webhooks fast: persist minimal event and enqueue job.
- Use async jobs for OCR/AI/file parsing/reconciliation.
- Add correlation IDs/log context where existing logging supports it.

---

## 12. Testing policy

Do not waste credits by running broad full-project tests after every tiny edit.

Testing sequence:

1. Implement the complete scoped functional slice.
2. Run focused tests for touched backend/frontend/worker modules.
3. Run broader tests only if the change affects shared infrastructure or breaks integration boundaries.

Backend:

- Add unit tests for deterministic business rules.
- Add controller contract tests for API boundaries.
- Add query service tests for read models.
- Add integration tests only when persistence/transaction behavior is important.
- Use scoped Maven selectors where possible.

Frontend:

- Run targeted lint/typecheck/build for the dashboard app.
- Add component or route tests only where repo convention supports them.
- Add Playwright/e2e only for important UI flows, not every small component.

AI worker:

- Mock model providers.
- Test schema validation and safety boundaries.
- Do not call real LLM providers in normal tests.

Do not run full Maven/Playwright/project-wide suites unless:

- requested by the user;
- preparing a freeze/gate;
- shared infrastructure was modified;
- scoped tests indicate a cross-layer break.

---

## 13. Git and worktree policy

Before modifying files:

```text
git status
```

Then:

- identify dirty files;
- do not modify unrelated dirty files;
- do not clean up the broader worktree;
- do not run destructive git commands;
- do not reset, checkout, stash, rebase, merge or delete files unless explicitly requested;
- do not commit unless explicitly requested;
- do not push unless explicitly requested.

After changes, report:

- changed files;
- whether files were pre-existing dirty;
- commands run;
- tests passed/failed;
- limitations.

Never hide partial completion.

---

## 14. Dependency and framework policy

Do not add dependencies unless necessary.

Before adding any dependency:

- check if existing codebase already has a solution;
- explain why existing tools are insufficient;
- consider security and maintenance cost;
- avoid dependency for trivial helpers;
- do not add new frameworks for one feature.

Forbidden without explicit approval:

- replacing Spring architecture;
- adding microservice framework;
- adding new database;
- adding new message broker;
- adding new frontend UI framework;
- adding new auth framework;
- adding production LLM provider calls;
- adding paid external services.

---

## 15. Documentation policy

Update only relevant docs.

For product/status slices, update:

- relevant capability slice doc;
- stage/status reconciliation doc;
- current-stage pointer if status changes;
- API contract docs if endpoint contracts change;
- security docs if security boundary changes.

Do not rewrite broad docs unless the task is documentation-specific.

Docs must be honest:

- PASS means verified;
- PARTIAL means explicitly limited;
- BLOCKED means blocked with reason;
- do not mark UI complete if only backend exists;
- do not mark external integration complete if only stub exists.

---

## 16. Code style policy

Write clear, direct, maintainable code.

Avoid:

- parasite complexity;
- over-engineered abstractions;
- vague names;
- excessive comments;
- fake demo-only logic hidden as production logic;
- random utility layers;
- broad refactors unrelated to the task;
- duplicated business rules;
- silent fallback behavior;
- swallowing exceptions.

Comments are allowed only when they explain non-obvious business/security reasoning.

Prefer explicit business names over generic names.

Bad:

```java
DataService
ProcessManager
HelperUtils
doThing()
handleStuff()
```

Better:

```java
QuoteConversionAttemptReviewQueryService
ChannelToQuoteWiringService
MarginGuardrailPolicy
ProductAliasMatcher
SubstituteRiskDecision
```

---

## 17. Backend implementation checklist for every feature

Before coding:

- inspect existing module/package conventions;
- locate current controllers/services/repositories/DTOs/tests;
- identify exact control flow;
- identify whether the slice is read-only or mutation;
- identify tenant/auth requirements;
- identify audit/outbox requirements;
- identify docs to update;
- identify scoped tests to run.

During coding:

- preserve existing architecture;
- keep controller thin;
- keep business rules out of controller;
- use command service for mutations;
- use query service/read model for reads;
- use DTOs at API boundary;
- enforce tenant scope;
- emit audit for important mutations;
- avoid unrelated changes.

After coding:

- run scoped verification;
- update relevant docs;
- report exact files changed;
- report exact commands and results;
- report limitations honestly.

---

## 18. Standard task execution format

When starting a task, follow this internal plan:

```text
1. Inspect git status and relevant files.
2. Locate existing architecture and tests.
3. Identify exact slice boundaries.
4. Implement backend control flow if required.
5. Implement frontend/API client/UI if required.
6. Add or update focused tests.
7. Update only relevant docs.
8. Run scoped verification.
9. Report results.
```

Expected output after a task:

```text
Summary:
- what was implemented

Changed files:
- file paths with short explanation

Control flow:
- controller -> service -> repository/read model -> DTO/UI

Verification:
- commands run
- pass/fail result

Security:
- tenant/auth/audit/write-path notes

Limitations:
- what remains intentionally not implemented

Next recommended slice:
- one concrete next step
```

---

## 19. Current OrderPilot domain priorities

Prioritize code that supports real business value:

- omnichannel/channel intake;
- RFQ to draft quote;
- PO to draft order;
- quote conversion review;
- operator exception cockpit;
- product alias/OEM/SKU matching;
- substitution engine;
- price/discount/margin validation;
- inventory availability and reconciliation;
- bot runtime with controlled handoff;
- audit and tenant-scoped read models;
- commerce intelligence dashboards;
- integration control with ChangeRequest.

Do not prioritize:

- generic chatbot builder;
- full accounting/tax engine;
- autonomous ERP writes;
- mobile-first app;
- advanced forecasting before clean data;
- 10 connector implementations before adapter contract is stable;
- broad redesigns without stage-gate reason.

---

## 20. Read-only slice rules

If a task is read-only:

- do not add mutation endpoints;
- do not add command buttons;
- do not create approval/reject/correct/retry behavior;
- do not update business state;
- do not emit mutation audit events unless existing read audit policy requires it;
- consume existing query APIs;
- render sanitized DTOs only;
- document that the slice is read-only.

---

## 21. Mutation slice rules

If a task is mutation-capable:

- define command DTO;
- define command service;
- enforce tenant/auth/policy;
- validate deterministic business rules;
- use transaction boundary;
- write domain state;
- append audit event;
- append outbox event if external/event workflow is involved;
- return response DTO;
- add controller/service tests;
- update docs with exact mutation behavior.

Do not implement mutation “just because UI needs a button.”

---

## 22. Definition of done

A slice is done only when:

- required business flow works;
- backend control flow is correct;
- UI/API surface exists if required;
- tenant isolation is preserved;
- security/write-path rules are preserved;
- errors are controlled;
- audit/outbox behavior is correct for the slice;
- tests or scoped verification pass;
- docs reflect actual status;
- limitations are explicit.

A slice is not done when:

- only docs changed but feature is claimed complete;
- only backend exists but required UI is missing;
- UI uses fake data while backend exists;
- unsafe fields are exposed;
- mutation exists without audit/policy;
- tests were skipped without reason;
- broad unrelated files were modified.

---

## 23. Final instruction

When in doubt, preserve the existing architecture and ask the task prompt for narrower scope.

Do not invent architecture.
Do not create visibility-only layers.
Do not hide risk.
Do not optimize for impressive-looking output over correct product behavior.

Build OrderPilot as a controlled, secure, production-leaning B2B SaaS core.
