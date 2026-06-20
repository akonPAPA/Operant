# AI Model Runtime Foundation (OP-CAP-39A)

Status: foundation. This document describes the bounded model-runtime contract for advisory model
review/report tasks. It does not enable production model calls, connector execution, ERP/1C writes,
or AI-to-business-table writes.

## Purpose

OrderPilot can use local or future remote models as controlled advisory tools. Models may help with
code review, business-logic review, product/security gate review, and later extraction advisory
tasks. They do not become business actors.

The required authority path is unchanged:

```text
model output -> advisory result -> deterministic backend validation/service -> human/operator gate
```

AI output never approves, executes, mutates, or overrides backend-owned authority.

## Runtime Contract

Backend foundation classes live under:

```text
apps/core-api/src/main/java/com/orderpilot/application/services/modelruntime
```

Roles:

- `CODE_REVIEW`
- `BUSINESS_LOGIC_REVIEW`
- `PRODUCT_SECURITY_GATE`
- `EXTRACTION_ADVISORY`

Provider types:

- `DISABLED`
- `OLLAMA_LOCAL`
- `REMOTE_PLACEHOLDER`

`REMOTE_PLACEHOLDER` is intentionally not runnable in OP-CAP-39A. It records future intent without
adding provider secrets, outbound network calls, or production remote integration.

Policy fields:

- model role
- provider type
- model id
- max context tokens
- max output tokens
- timeout
- sequential execution flag
- heavy model flag
- enabled flag

Safe production default is `DISABLED`, sequential, no heavy model.

Default local review rota:

- `qwen3-coder:30b`
- `qwen3:30b`

Optional heavy reviewer:

- `deepseek-r1:32b`

The heavy reviewer is not in the default rota. OP-CAP-38/COORD measured `qwen3-coder:30b` and
`qwen3:30b` as reliable one-at-a-time reviewers, while `deepseek-r1:32b` failed twice and crashed
local Ollama.

## Run Metadata

Safe run metadata captures:

- model id
- role
- provider
- startedAt / finishedAt
- duration
- status
- bounded failure reason
- prompt/eval token counts when available
- output/eval token counts when available

It does not store raw prompt text, raw model output, secrets, customer text, DB dumps, full repository
dumps, connector credentials, internal idempotency hashes, or write authority.

## Advisory Output

Allowed advisory output:

- findings
- risk classification
- suggested tests
- confidence
- caveats
- next review recommendation

Rejected authority/action categories:

- approve ChangeRequest
- execute connector
- mutate quote/order/inventory/price/customer state
- override tenant/actor/status/approval/execution
- bypass validation

The backend guard treats these as non-advisory and rejects them as model output authority.

## Safety Boundaries

- No connector execution.
- No ERP/1C write.
- No AI-to-business-table write.
- No frontend-to-DB path.
- No autonomous agent loop that edits the repository.
- No broad AI platform, model marketplace, tool-calling framework, queue, vector DB, or new
  infrastructure.
- No secrets in prompts, logs, reports, or metadata.
- No raw full repository input, raw DB dump, or unsanitized customer PII in model input.
- Tenant isolation, audit, idempotency, external-write safety, and OP-CAP-37 ChangeRequest semantics
  remain unchanged.

## Local Harness Alignment

The local review harness remains script-level advisory tooling:

```text
scripts/local-ai-review/run-local-ai-review.ps1
```

Defaults:

- qwen reviewers only
- sequential execution
- `num_ctx=8192`
- `num_predict=3000`
- timeout 1800 seconds
- safe input package built from allowlisted files, scoped diff, summary, and filtered git metadata

Heavy opt-in:

- pass `-IncludeHeavyReviewer`
- `num_ctx=6144`
- `num_predict=1500`
- timeout 900 seconds

The script filters denied input paths including `.env`, secrets/credentials/private keys, dependency
folders, build output, caches, DB/dump files, and local artifacts.
