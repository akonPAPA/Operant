# OrderPilot Core API Agent Instructions

## Scope

This directory contains the Java 21 + Spring Boot Core API.

OrderPilot is an investor-grade B2B SaaS transaction intelligence platform for auto and industrial parts distributors. The Core API is the authority boundary for tenant-scoped business truth, controlled mutations, audit records, and approval workflows.

The Core API owns business truth:
- tenants
- users
- permissions
- customers
- products
- inventory
- pricing
- discount rules
- margin rules
- validation
- quotes
- orders
- substitution
- audit
- change requests
- integration command workflow

Python AI workers, chatbots, connectors, and frontend clients must not bypass the Core API write path.

## Non-Negotiable Write Path

- AI, chatbot, frontend, connector, and worker code must never directly write to master business data.
- All business mutations must go through typed backend services.
- Mutations must enforce authentication, tenant policy, deterministic validation, database transactions, audit events, and approval gates where required.
- Preserve tenant isolation for every controller, service, repository call, job, webhook, import, export, fixture, and test.
- Preserve audit-first behavior. State transitions, approvals, external commands, validation outcomes, and security-relevant decisions must remain durably explainable.
- Preserve the ChangeRequest and approval model for external writes. Integrations, bots, and AI suggestions may propose changes, but risky or externally sourced writes must move through the approval workflow.
- Do not weaken production security, tenant policy, validation, transactions, approvals, or audit behavior to make tests or demos pass.

## Security-critical classes

Treat these as security-sensitive:

- ApiPermissionGuard
- ApiPermissionInterceptor
- ApiSecurityWebConfig
- TenantContext
- TenantPolicyService
- AuditEventService
- ChangeRequest services
- Connector command services
- Draft quote/order command services
- Validation services

Do not weaken these classes unless explicitly instructed and justified.

## WebMvcTest rules

If `@WebMvcTest` fails because MVC slice context lacks collaborators:

- Add narrow test-only config under `src/test/java`.
- Use `@MockBean` only for service collaborators not under test.
- Use permission headers when testing protected endpoints.
- Use test-only noop permission config only when the test is not about permission behavior.
- Do not modify production security to satisfy a slice test.

## Test rules

- Do not disable tests.
- Do not use `skipTests` or `maven.test.skip`.
- Inspect Surefire reports first when Maven reports failures:

```powershell
Get-ChildItem -Recurse target/surefire-reports
Get-Content target/surefire-reports/*.txt
```

- Use focused tests before full-suite verification when backend behavior changes.
- Use test-scope configs for `@WebMvcTest` slice issues.
- Do not weaken production security to fix tests.

## Spring Boot 3 rules

Use `jakarta.*`, not `javax.*`, for servlet/Jakarta EE APIs.

Do not add legacy `javax.servlet-api` just to fix imports.

## Maven verification

Preferred commands:

```powershell
mvn -Dtest=SpecificTest test
mvn -Dtest=SpecificTest#specificMethod test
mvn clean test
```

Never run:

```powershell
mvn -DskipTests ...
mvn -Dmaven.test.skip=true ...
```

## Required output format

Future AI agents should close implementation tasks with:

- Assumptions
- Files changed
- Safety explanation
- Tests run
- Risks/blockers
- Next step
