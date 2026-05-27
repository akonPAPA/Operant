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
