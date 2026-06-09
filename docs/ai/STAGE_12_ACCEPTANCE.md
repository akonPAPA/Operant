# Stage 12 — AI Worker Understanding Layer: Acceptance Matrix

Stage 12 is the AI-worker advisory understanding / local-model-runtime / provider-safety / evaluation
stage for the Python AI worker (`apps/ai-worker`). This document is the closeout checklist: what Stage
12 includes, what it deliberately excludes, and the verifiable acceptance items.

It builds on and does not restate:

- [`AI_UNDERSTANDING_LAYER.md`](AI_UNDERSTANDING_LAYER.md) — advisory layer, schema, providers.
- [`LOCAL_MODEL_RUNTIME.md`](LOCAL_MODEL_RUNTIME.md) — local Ollama provider + provider-mode wiring.
- [`AI_EVALUATION_HARNESS.md`](AI_EVALUATION_HARNESS.md) — offline evaluation/safety harness.

## What Stage 12 includes

- **12A** — advisory `ExtractionResult` schema, semantic extraction pipeline, schema validation,
  prompt-injection detection foundation.
- **12B** — local open-source Ollama-compatible runtime provider, `LocalModelConfig`, safe local-only
  (loopback, credential-free, no-auth) transport design, safe metadata/provenance, no paid dependency.
- **12C** — provider-mode wiring: `ProviderMode`, `provider_mode_from_env()`, the single
  `build_extraction_provider()` factory, job-handler integration, security allowlist, fail-closed local
  mode.
- **12D** — fixture-driven provider evaluation harness (`orderpilot_ai_worker/evaluation/`).
- **12-FINAL** — hardening pass: explicit business-action key denylist on local model output and in the
  evaluation harness; broadened prompt-injection phrase coverage; this acceptance matrix.

## What Stage 12 deliberately does NOT include

- No paid/hosted provider integration (no OpenAI/Anthropic/Azure wiring).
- No real Ollama dependency, real network, or paid key in CI/tests.
- No business writes: no quote/order/inventory/customer/price/discount/margin/ERP/1C mutation, no
  approvals, no connector writes, no autonomous AI action.
- No core-api, web-dashboard, database-migration, or backend command-service changes.
- No tenant-level model policy, no broader queue routing policy, no production model accuracy
  benchmark or labeled-accuracy dataset.

## Safety model (unchanged)

> AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records.

AI output is **advisory only** and untrusted until schema validation and deterministic Core API
validation. The worker has no executable business/tool surface; the advisory schema has no action field.

## Supported provider modes

| Mode | Network | Default | Behavior |
| --- | --- | --- | --- |
| `RULE_BASED` | none | **yes** | Deterministic offline understanding extractor. |
| `MOCK_SEMANTIC` | none | tests/demo | Rule-based + 11I scenario provenance. |
| `LOCAL_OLLAMA` | loopback only, opt-in | no | Local open-source runtime; double-gated (see below). |
| `FUTURE_SEMANTIC` | none | rejected | Placeholder; rejected by the security envelope, fails closed. |

`LOCAL_OLLAMA` is **double-gated**: the mode must be selected (`ORDERPILOT_AI_PROVIDER_MODE` or a per-job
`requested_pipeline`) **and** `LocalModelConfig` must be ready (`ORDERPILOT_AI_LOCAL_MODEL_ENABLED=true`
+ endpoint + model). Only then is a transport built. Otherwise no network client is constructed and the
provider fails closed.

## Fail-closed convention (single convention)

Invalid/unsafe provider or model output becomes a **controlled advisory `ExtractionResult`**, never
`None`:

- `validation_status="failed"`, `advisory_only=True`;
- no line items, no unsafe extracted business fields;
- no command/action/tool-call/ERP-write surface;
- confidence zero or safely low;
- a bounded, safe failure reason (no secrets, endpoint, payload, or customer text).

`None` is used only at the outer job level when provider *resolution* fails before extraction starts
(unknown/unrunnable mode), which is reported as a controlled `FAILED` job result.

## Acceptance matrix

| # | Acceptance item | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Rule-based is the default provider | ✅ | `provider_mode_from_env()` → `RULE_BASED`; `test_local_provider_mode_wiring.py::test_default_job_uses_rule_based_offline` |
| 2 | Local model is explicit opt-in (not default) | ✅ | `models.py` default mode; `LOCAL_MODEL_RUNTIME.md` |
| 3 | Disabled local mode builds/calls no transport | ✅ | `test_local_provider_mode_wiring.py::test_local_mode_disabled_does_not_build_transport`; harness `local_disabled_fail_closed` |
| 4 | Missing endpoint/model builds/calls no transport | ✅ | `test_local_ollama_provider.py::test_missing_endpoint_fails_closed` / `test_missing_model_fails_closed`; harness `local_missing_endpoint_fail_closed` |
| 5 | Schema-invalid local response → controlled failure | ✅ | `test_local_ollama_provider.py::test_schema_invalid_output_fails_closed`; harness `local_schema_invalid_response` |
| 6 | Prompt injection guarded (review + low confidence) | ✅ | `test_prompt_injection_pipeline.py`; `test_local_ollama_provider.py::test_prompt_injection_in_input_is_flagged_not_obeyed` |
| 7 | No unsafe line items / partial data on failure | ✅ | harness `no_unsafe_partial_business_data` check; `summary.unsafe_partial_data_violations == 0` |
| 8 | No action/tool/ERP-write surface in output | ✅ | `local_ollama._UNSAFE_KEYS`; `evaluator._FORBIDDEN_ACTION_KEYS`; `test_business_action_key_in_output_fails_closed`; harness `local_business_action_surface_rejected` |
| 9 | Future/unwired provider mode rejected/fail-closed | ✅ | `security.ALLOWED_PIPELINES`; `test_local_provider_mode_wiring.py::test_future_semantic_mode_*`; harness `future_mode_rejected` |
| 10 | Evaluation harness runs offline | ✅ | `tests/evaluation` (fake transport only) |
| 11 | No real network in tests | ✅ | all transports are in-process fakes; loopback URL validator tests |
| 12 | No AI direct business writes | ✅ | no mutation path anywhere in the worker; advisory schema has no action field |
| 13 | No backend/frontend/migration scope | ✅ | change surface limited to `apps/ai-worker/**` + `docs/ai/**` |

Current default-suite summary (deterministic): 13 cases, 13 passed, 80/80 checks, 6 fail-closed,
2 provider/schema failures, 1 prompt-injection guarded, **0 unsafe-partial-data violations**.

## How provider resolution works

`build_extraction_provider(mode, *, local_config=None, local_transport=None)` is the single selection
point. The job handler (`process_ai_extraction_job`) resolves through it and never bypasses it. Unknown
modes raise a typed `ProviderResolutionError` before any provider/transport exists; the handler turns
that into a controlled `FAILED` result.

## How prompt injection is handled

Customer text is treated as hostile content. `detect_prompt_injection` matches known hostile phrases
(instruction-override, secret/data exfiltration, tool/backend abuse, SQL/credential/admin abuse,
business-mutation attempts) and tags them as **content** that forces `needs_review`, caps confidence
(≤ 0.25), and records advisory risk signals. Phrases are never executed — the worker has no tool or
mutation surface, and the schema has no executable field.

## Targeted test commands (from `apps/ai-worker`)

```
./.venv/Scripts/python.exe -m pytest tests/extraction/test_local_ollama_provider.py -q -p no:cacheprovider
./.venv/Scripts/python.exe -m pytest tests/extraction/test_local_provider_mode_wiring.py -q -p no:cacheprovider
./.venv/Scripts/python.exe -m pytest tests/evaluation -q -p no:cacheprovider
./.venv/Scripts/python.exe -m pytest tests/jobs -q -p no:cacheprovider
./.venv/Scripts/python.exe -m pytest tests/security/test_prompt_injection_pipeline.py -q -p no:cacheprovider
```

## Known limitations

- No real local-model quality benchmark and no labeled-accuracy dataset — the harness is a
  behavior/safety regression tool, not an accuracy benchmark.
- No tenant-level model policy and no broader queue-routing policy yet.
- No production model observability/telemetry beyond bounded structured job logging.
- Real local runtime requires the developer to run an Ollama-compatible server separately; CI never
  does, and provider-mode wiring does not change Core API deterministic-validation authority.
