# Core V1 Demo Readiness Checklist

Use this checklist before a local design partner, pilot, or investor demo. Mark each item PASS, FAIL, or N/A with a short note.

## A. Local Environment

| Check | Status | Notes |
| --- | --- | --- |
| Backend starts on `http://localhost:8080` |  |  |
| Frontend starts on `http://localhost:3000` |  |  |
| AI worker tests pass via `.venv\Scripts\python.exe -m pytest` |  |  |
| PostgreSQL and Redis are available locally |  |  |
| `docker compose -f infra\docker\docker-compose.yml ps` shows expected services when using Compose |  |  |
| `scripts\check-local-demo.ps1` passes or blockers are understood |  |  |
| `git status --short` shows no tracked dirty files |  |  |
| No local junk is staged: `.env`, `.venv`, `node_modules`, `target`, `.next`, coverage, logs, IDE files |  |  |

## B. Security Boundaries

| Check | Status | Notes |
| --- | --- | --- |
| Production connectors are disabled |  |  |
| Real ERP/1C writes are disabled |  |  |
| External network connector calls are disabled |  |  |
| Raw secrets do not appear in UI, API responses, audit metadata, logs, or docs |  |  |
| Idempotency hash is masked/displayed as `connectorIdempotencyKeyHash` with `sha256:*` value |  |  |
| Bot-triggered connector commands are blocked |  |  |
| Stage 9 integration path does not mutate inventory |  |  |

## C. Stage 9B Safety

| Check | Status | Notes |
| --- | --- | --- |
| Approved validation-backed demo ChangeRequest can execute through Demo ERP only |  |  |
| Duplicate execute replays safely |  |  |
| Replay does not increment attempts |  |  |
| Replay does not create a second sync event |  |  |
| Replay does not create a Stage 9B `ConnectorCommand` |  |  |
| Non-demo target is policy-blocked |  |  |
| Audit timeline shows attempt, success, failure, replay, cancel, and policy-block events where data exists |  |  |
| Tenant isolation tests exist and pass for Stage 9 integration access |  |  |
| `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED` is visible for blocked execution |  |  |

## D. Demo Quality

| Check | Status | Notes |
| --- | --- | --- |
| Founder demo talk track can be followed |  |  |
| Investor 3-minute script can be followed |  |  |
| Investor 10-minute script can be followed |  |  |
| Demo runbook commands match current repo paths |  |  |
| Key screens have usable empty/loading/error states for the available local data |  |  |
| Demo docs match current UI/API names, including `ChangeRequest`, `Demo ERP`, and `connectorIdempotencyKeyHash` |  |  |
| Demo close clearly states what is intentionally disabled |  |  |

## E. Blockers Before Real Pilot

| Blocker | Status | Notes |
| --- | --- | --- |
| `connector_idempotency_key` column rename is deferred |  | Stores `sha256:*` hash values only; later migration should rename for clarity. |
| GitHub CI/dependabot cleanup is pending |  | Does not block local demo work unless it affects the requested local task. |
| Production connector acceptance gate is not implemented |  | Requires separate security/runbook acceptance. |
| Real customer data import process needs a separate pilot checklist |  | Do not use demo seed scripts for production data. |
| Production credential custody is not enabled for connectors |  | Requires a real secrets manager and separate acceptance. |
