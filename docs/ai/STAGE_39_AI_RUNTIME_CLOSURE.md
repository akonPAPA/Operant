# Stage 39 AI Runtime Closure

Status: closed for Stage 39. Handoff target: Stage 40 API Security.

## Stage 39A Summary

Stage 39A established the bounded model-runtime foundation. The safe default provider is disabled,
local Ollama review is manual/operator-controlled, run metadata is bounded, and model output remains
advisory. The local smoke test was completed by the operator before Stage 39D.

## Stage 39B Summary

Stage 39B hardened Core API AI result intake. Core rejects unknown jobs, tenant/source/pipeline
mismatches, malformed or oversized payloads, unsafe business mutation fields, actor/permission/status
override attempts, approval/execution attempts, connector/ERP/1C/write command attempts, duplicate
conflicting terminal results, and unsafe replay behavior. Same terminal result replay is deterministic
and idempotent.

## Stage 39C Summary

Stage 39C added the hostile fixture/evaluation harness for AI Worker and result handoff. The fixture
suite covers normal RFQ, messy RFQ, ambiguous RFQ, prompt injection, unsafe model output, malformed
model output, and replay/conflict paths. CI-safe tests use deterministic providers and injected fake
local-runtime transport, not real Ollama.

## Stage 39D Summary

Stage 39D closes the runtime boundary with production-safe default verification, an opt-in
local-runtime adversarial matrix, safe persisted evaluation report output, and a manual local Ollama
runbook. The normal offline suite remains runnable without real Ollama.

## Current AI Trust Boundary

AI output is untrusted advisory extraction data only:

```text
AI Worker output -> advisory result schema -> Core API handoff validation -> deterministic backend
services -> approval/change-control paths -> audited business mutation
```

The AI worker cannot choose tenant, actor, permission, status, approval, execution state, connector
target, or external write behavior. Core API owns those decisions.

## AI Is Allowed To Do

- Extract advisory RFQ/order text hints.
- Emit candidate SKU/OEM/description/quantity/UOM evidence.
- Mark uncertainty, ambiguity, low confidence, and prompt-injection risk.
- Produce bounded model/runtime metadata safe for diagnostics.
- Fail closed with safe reason tokens.

## AI Is Forbidden To Do

- Create or mutate quote, order, customer, product, price, margin, stock, or inventory state.
- Approve, reject, execute, or transition trusted workflow state.
- Call connectors, ERP, 1C, tools, SQL, shell, or external write paths.
- Choose or override tenant, actor, user, permissions, status, approval, or execution state.
- Provide connector credentials, internal IDs, raw payloads, raw model output, stack traces, secrets,
  filesystem paths, or idempotency internals in API responses.

## Production-Safe Defaults

- `RULE_BASED` remains the default worker provider.
- `LOCAL_OLLAMA` requires explicit provider-mode selection plus
  `ORDERPILOT_AI_LOCAL_MODEL_ENABLED=true`, endpoint, and model.
- Local endpoint is restricted to credential-free loopback HTTP(S): `localhost`, `127.0.0.1`, `[::1]`.
- Default prompt size limit is `20000` chars.
- Default response size limit is `200000` chars.
- Default timeout is `30` seconds.
- Missing config, invalid endpoint, timeout, network/runtime error, malformed output, unsafe output,
  and schema-invalid output fail closed.

## Tests And Commands

Worker evaluation and handoff:

```powershell
cd apps\ai-worker
.\.venv\Scripts\python.exe -m pytest tests\evaluation\test_provider_evaluation_harness.py tests\jobs\test_ai_result_handoff.py tests\jobs\test_ai_job_security.py
```

Opt-in Stage 39D local-runtime matrix:

```powershell
cd apps\ai-worker
$env:ORDERPILOT_STAGE39D_LOCAL_ADVERSARIAL = "1"
.\.venv\Scripts\python.exe -m pytest tests\evaluation\test_stage39d_local_ollama_adversarial_optin.py
```

Safe report output:

```powershell
cd apps\ai-worker
.\.venv\Scripts\python.exe scripts\write_stage39_evaluation_report.py .\stage39-evaluation-report.json
```

Core API targeted regression:

```powershell
mvn test "-Dtest=AiWorkerHostileFixtureStage39CTest,AiWorkerResultIntakeServiceTest,AiWorkerResultHandoffWiringStage13BTest"
mvn test "-Dtest=opcap30.WorkerResultDrainConcurrencyH2Test,opcap30.WorkerStaleRecoveryRaceH2Test"
```

## Known Limitations

- Stage 39 does not certify extraction accuracy or model quality.
- Real Ollama adversarial behavior is manual/operator-local, not default CI.
- No dashboard, scheduled eval, or trend infrastructure is introduced.
- Remote paid AI providers remain out of scope.
- API authentication/authorization redesign remains out of scope.

## Handoff To Stage 40

Stage 40 should address API security posture such as global authentication/authorization contracts,
permission defaults, principal mapping, gateway/JWT/OIDC decisions, and related API security tests.
Stage 39 intentionally did not change `ApiSecurityWebConfig`, `ApiPermissionInterceptor`, Stage 8/9
permissions, or global auth behavior.
