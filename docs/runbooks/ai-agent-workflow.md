# AI Agent Workflow

This runbook defines the default workflow for Codex, Claude, and other AI coding agents working on OrderPilot.

## Start Every Task

1. Read the relevant instruction files before editing:

```powershell
Get-Content AGENTS.md
Get-Content apps/core-api/AGENTS.md
```

2. Confirm the active repository and current diff:

```powershell
git rev-parse --show-toplevel
git status --short
git diff --stat
```

3. Identify the smallest set of files needed for the task. Read nearby docs, tests, and implementation before deciding how to edit.

4. Restate any assumptions that affect safety, scope, tenant isolation, approvals, or audit behavior.

## Keep Tasks Small

- Make one coherent change at a time.
- Avoid unrelated refactors, formatting churn, dependency changes, or package reshaping.
- Do not modify production Java code, tests, or `pom.xml` during documentation-only tasks.
- Preserve the Core API as the typed service boundary for business mutations.
- Keep AI, chatbot, frontend, and connector layers out of direct master-data writes.
- Preserve tenant isolation, deterministic validation, transactions, audit events, and approval gates.

## Verify The Diff

Before finishing, inspect what changed:

```powershell
git status --short
git diff -- AGENTS.md apps/core-api/AGENTS.md docs/runbooks/ai-agent-workflow.md
```

For code tasks, also inspect the complete relevant diff:

```powershell
git diff --stat
git diff -- apps/core-api
```

Do not revert or overwrite unrelated user changes. If unrelated files are already modified, leave them alone and report only the files touched by the current task.

## Test Strategy

Run focused tests first when behavior changes. From `apps/core-api`:

```powershell
mvn -Dtest=SpecificTest test
mvn -Dtest=SpecificTest#specificMethod test
```

Then run the full suite when the change scope warrants it:

```powershell
mvn clean test
```

Do not disable tests and do not run Maven with `skipTests` or `maven.test.skip`.

When Maven fails, inspect Surefire reports before changing code:

```powershell
Get-ChildItem -Recurse target/surefire-reports
Get-Content target/surefire-reports/*.txt
```

For `@WebMvcTest` slice failures, prefer narrow test-scope configuration under `src/test/java`, targeted mocks, or test-only permission configuration when the test is not about permission behavior. Do not weaken production security to fix tests.

For documentation-only tasks, no Maven test run is required. Use a lightweight verification such as `git diff --check` when available.

## Avoid Local Lag

Do not enable verbose Hibernate SQL logging by default. It slows local development, makes logs hard to scan, and can bury security or validation signals.

Only enable SQL logging temporarily for a focused debugging session, preferably through local or test-scoped configuration. Remove or disable verbose SQL logs before finishing unless the task explicitly requires persistent diagnostics.

## Required Final Output

End implementation tasks with:

- Assumptions
- Files changed
- Safety explanation
- Tests run
- Risks/blockers
- Next step
