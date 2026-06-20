# AI Worker Provider Evaluation Harness (OP-CAP-12D)

Status: foundation. OP-CAP-12D adds a small, **offline, fixture-driven evaluation/safety harness** for
the AI worker's advisory extraction providers. It lets OrderPilot compare provider behavior — schema
validity, `advisory_only` status, confidence, prompt-injection handling, and fail-closed routing —
without touching backend, frontend, databases, migrations, paid APIs, or real network providers.

Read [`AI_UNDERSTANDING_LAYER.md`](AI_UNDERSTANDING_LAYER.md) for the advisory layer and
[`LOCAL_MODEL_RUNTIME.md`](LOCAL_MODEL_RUNTIME.md) for the local provider mode it can exercise.

## What it is

A regression and safety harness for provider behavior. It:

- reuses the **existing** provider factory (`jobs.provider_factory.build_extraction_provider`), the
  **existing** `SemanticExtractionPipeline`, and the **existing** `ExtractionResult` schema;
- runs a list of typed `EvaluationCase`s through a chosen `ProviderMode` and inspects the advisory
  result;
- for `LOCAL_OLLAMA` cases injects a deterministic **in-process fake transport** (built from a canned
  response body) so the local runtime path is exercised with no installed model and no real HTTP;
- asserts the existing fail-closed convention and rolls up a deterministic `EvaluationSummary`.

## What it is NOT

- **Not** a model benchmark / accuracy suite. There are no labeled-accuracy or statistical-quality
  metrics — counts only (passed/failed checks, fail-closed, schema-failure, injection-guarded,
  unsafe-partial-data violations).
- **Not** a second extraction pipeline. It composes the existing pipeline; it never re-implements
  sanitize/injection/validation logic.
- **Not** a business-write path. It only reads advisory output; it creates/approves nothing.
- **Not** a real-Ollama test. CI never starts or requires an Ollama server, never makes a network
  call, and never needs a paid API key. Disabled/misconfigured/unknown-mode cases assert the transport
  is never called at all.

## Package

`apps/ai-worker/orderpilot_ai_worker/evaluation/`

- `models.py` — typed `EvaluationCase`, `ExpectedExtraction`, `EvaluationResult`,
  `EvaluationFinding`, `EvaluationSummary` (pydantic, same style as the extraction schemas).
- `evaluator.py` — `evaluate_case`, `evaluate_cases`, `summarize`, `run_default_evaluation`, the
  in-process `_SpyTransport`, and Stage 39D bounded JSON report helpers.
- `cases.py` — `default_evaluation_cases()`, the bundled inline fixture suite (no binary fixtures).

## Checks

Universal invariants asserted for every case that produces an extraction:

- the result is a schema-valid `ExtractionResult`;
- `advisory_only` is `True`;
- **no unsafe partial business data** — when `validation_status == "failed"`, there are no line items
  and no extracted fields;
- **no executable action surface** — the serialized result exposes no command/tool/mutation JSON key
  (`action`, `command`, `approve`, `execute`, `sql`, `erp_write`, `tool_call`, …).

Case-specific (opt-in) checks: controlled failure, failed-result-has-no-line-items, not-failed,
prompt-injection-guarded (`needs_review` + confidence ≤ 0.25 + `unsafe_instruction`),
transport-called-as-expected, expected intent, line-item presence, and provider-resolution-fails-closed
for unknown/unrunnable modes.

## Fail-closed convention (unchanged)

The harness asserts the **existing** convention — it does not invent a second one. A schema-invalid /
unparseable / disabled / misconfigured provider becomes a controlled `ExtractionResult` with
`validation_status="failed"`, `advisory_only=True`, zero/low confidence, no line items, and no partial
unsafe business data. An unknown/unrunnable provider mode (e.g. `FUTURE_SEMANTIC`) fails closed at
resolution (`ProviderResolutionError`) before any provider or transport exists.

## Bundled cases

`default_evaluation_cases()` covers: a valid Telegram-style RFQ, a PDF/PO-like multi-line text, an
ambiguous/missing-SKU line, a malformed quantity/UOM line, blank input, out-of-domain text, a
prompt-injection input, a valid `LOCAL_OLLAMA` response (fake transport), a schema-invalid
`LOCAL_OLLAMA` response, a disabled `LOCAL_OLLAMA` mode, a missing-endpoint `LOCAL_OLLAMA` mode, and an
unknown `FUTURE_SEMANTIC` mode. Inputs are synthetic and carry no secrets or realistic key prefixes.

## Running the targeted tests

From `apps/ai-worker`:

```
./.venv/Scripts/python.exe -m pytest tests/evaluation -q -p no:cacheprovider
```

The harness is also callable as a plain Python service (no CLI dependency added):

```python
from orderpilot_ai_worker.evaluation import run_default_evaluation
summary = run_default_evaluation()
assert summary.all_passed and summary.unsafe_partial_data_violations == 0
print(summary.model_dump_json(indent=2))
```

Stage 39D safe report output:

```
./.venv/Scripts/python.exe scripts/write_stage39_evaluation_report.py ./stage39-evaluation-report.json
```

The report includes case id, category, pass/fail, safe reason token, and safety status. It excludes
raw hostile prompts and raw model output.

## Scope and limitations

- AI worker only. No core-api, web-dashboard, migration, real-network, or paid-provider changes.
- Deterministic providers and a fake local transport only; real local-model quality is **not** measured
  here (no labeled data). Provenance/accuracy of a real model remains the developer's responsibility
  when running an Ollama-compatible server separately.
- This harness does not change Core API deterministic-validation authority; it strengthens the
  worker-side acceptance that *bad AI output → schema validation rejects it, no business write occurs*.
