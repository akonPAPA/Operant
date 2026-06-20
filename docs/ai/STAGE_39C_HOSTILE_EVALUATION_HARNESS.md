# Stage 39C Hostile AI Evaluation Harness

Status: hardening.

## Purpose

Stage 39C adds a repeatable offline adversarial harness for AI Worker extraction and Core API result
intake. The harness proves that normal RFQ extraction remains advisory-only, while hostile, malformed,
or model-injected outputs fail closed or route to review without business mutation.

## Fixture Categories

- `normal_rfq`: clear customer request, product-like text, quantity and UOM present.
- `messy_rfq`: inconsistent formatting, multiple product-like lines, noisy customer text.
- `ambiguous_rfq`: unknown or ambiguous product text, missing quantity/UOM, review or low confidence.
- `prompt_injection`: customer text attempts instruction override, quote/order creation, tenant/actor/status override.
- `unsafe_model_output`: model emits action/create quote/order/tool/write/connector/ERP/1C/authority fields.
- `malformed_model_output`: invalid JSON, wrong top-level type, missing required fields, oversized output.
- `replay_conflict`: Core result-intake replays and conflicting terminal results.

## Trust Boundary

The AI Worker harness uses deterministic rule-based extraction and fake `LOCAL_OLLAMA` transport only.
Normal CI does not require Ollama, network, paid provider credentials, or enabled local runtime.

Core API treats all model output as untrusted advisory data. Result intake may persist only advisory
extraction run/result state, bounded audit metadata, and the processing job terminal state. It must not
create or mutate quotes, orders, customers, products, price rules, inventory, connector commands, ERP/1C
writes, approval, execution, actor, permissions, or tenant state.

## Test Commands

AI Worker hostile harness:

```bash
cd apps/ai-worker
.\.venv\Scripts\python.exe -m pytest tests/evaluation/test_provider_evaluation_harness.py tests/jobs/test_ai_result_handoff.py tests/jobs/test_ai_job_security.py
```

Core API targeted hostile intake:

```bash
cd apps/core-api
mvn test "-Dtest=AiWorkerHostileFixtureStage39CTest,AiWorkerResultIntakeServiceTest"
```

Relevant worker lifecycle/concurrency regression:

```bash
cd apps/core-api
mvn test "-Dtest=opcap30.WorkerResultDrainConcurrencyH2Test,opcap30.WorkerStaleRecoveryRaceH2Test"
```

Real Ollama smoke tests remain manual/opt-in and are not required for Stage 39C CI.
