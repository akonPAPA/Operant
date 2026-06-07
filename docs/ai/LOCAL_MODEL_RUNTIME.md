# Local Model Runtime (OP-CAP-12B)

Status: foundation. OP-CAP-12B adds a **local open-source model runtime adapter** to the AI worker's
advisory understanding layer. The first supported shape is an **Ollama-compatible local HTTP
endpoint** (e.g. `http://localhost:11434`).

This is **not** a paid API integration. It is **not** OpenAI/Anthropic/Azure. It is **not** production
autonomous AI. It is a local model runtime seam that calls a locally running open-source model, parses
its structured JSON output, sanitizes and schema-validates it, and **fails closed** on anything
unsafe or invalid.

Read [`AI_UNDERSTANDING_LAYER.md`](AI_UNDERSTANDING_LAYER.md) first for the overall advisory layer and
safety model.

## Safety boundary (unchanged)

> AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records.

The local model is **untrusted input**, exactly like any other provider's output. It can never create
or approve quotes, orders, inventory, customers, prices, margin/discount rules, integration settings,
tenant data, or ERP/1C data. Deterministic Core API validation remains authoritative for
SKU/customer/price/stock/margin/substitution decisions. The worker has no mutation path and the
schema has no executable action field.

## Provider

`apps/ai-worker/orderpilot_ai_worker/extraction/providers/local_ollama.py`

- `LocalOllamaExtractionProvider` — implements the existing `SemanticExtractionProvider` contract
  (`extract(text, source_channel_context) -> ExtractionResult`).
- `LocalModelConfig` — `enabled`, `endpoint_url`, `model`, `timeout_seconds`, `temperature`,
  `max_prompt_chars`, `max_response_chars`, `runtime_name`, `runtime_version`; `.from_env()`,
  `.is_ready`, `.endpoint_host()`, `.safe_metadata()`.
- `LocalRuntimeResponse(status_code, body)` — transport-agnostic, carries no headers/secrets.
- `LocalModelError(reason)` — recoverable runtime failure with a bounded, safe reason token.
- `build_local_extraction_prompt(text, config)` — JSON-only, advisory, injection-as-risk prompt.
- `build_urllib_transport()` — **production-only** stdlib (`urllib`) transport; never used in tests
  and never a default. Local Ollama endpoints are unauthenticated, so no key/Authorization is sent.

### Disabled and fail-closed by default

The provider runs only when **explicitly enabled** with an endpoint and a model **and** an injected
transport. Dependency injection of the transport is what keeps tests (and CI) entirely off the
network.

## Request shape (isolated to the provider)

An Ollama `POST {endpoint}/api/generate` body is built only inside the provider:

```json
{ "model": "<local-model-name>", "prompt": "<extraction prompt>", "stream": false,
  "options": { "temperature": 0.0 } }
```

No Ollama-specific payload logic leaks outside the provider.

## Prompt construction

`build_local_extraction_prompt` produces a tightly scoped prompt that: requires a single JSON object
only; states AI is advisory only and must not approve/execute/mutate/promise anything; lists the
compact extraction schema (intent, language, customer hints, line items with SKU/OEM/vehicle context,
commercial context, risk signals, operator summary, confidence); instructs the model to preserve
uncertainty/low confidence and include evidence; and instructs the model to flag prompt injection as
a risk signal rather than obey it. The customer/document text is fenced as **untrusted DATA**. The
prompt contains no secrets, no credentials, no tenant data, and no unrelated business records.

## Response parsing

The provider accepts common local-runtime forms and stays strict:

- Ollama envelope `{"response": "...json..."}` — the inner generated text is unwrapped and parsed.
- A direct JSON object (e.g. a fake/test transport returning structured data).
- JSON wrapped in ```` ```json ... ``` ```` code fences.
- A single JSON object with leading/trailing prose (extracted by first `{` … last `}`).

Strictness: multiple/ambiguous JSON objects fail closed; output incompatible with `ExtractionResult`
fails closed; `advisory_only` is forced `true` after parsing; `overall_confidence`/
`document_confidence` are clamped to `[0,1]`; model-claimed `model_metadata` is discarded and replaced
with trusted, safe values.

## Model metadata (safe)

`model_metadata` is populated with: `provider = "local_ollama"`, the configured `model`, the bounded
`endpoint_host` (host[:port] only — never credentials/path/query/keys), `prompt_version`,
`schema_version`, and `parse_status`. No API keys, no Authorization headers, no raw secrets are ever
recorded. `extraction_method` is set to `local_runtime`.

## Risk handling

Prompt-injection / unsafe-instruction handling is **composed** with the existing 12A pipeline tagging
(no duplicated phrase list): when the source text contains injection patterns the result gets
`risk_signals.prompt_injection_suspected = true`, `risk_signals.unsafe_instruction = true`,
`validation_status = needs_review`, and confidence capped (≤ 0.25). Injected instructions are never
treated as commands.

## Fail-closed cases

The provider returns a controlled failure (raises a typed error the pipeline turns into a safe
advisory failure result) when:

- `enabled` is false, `endpoint_url` is missing, `model` is missing, or the transport is missing;
- the request times out (`local_runtime_timeout`) or the endpoint is unreachable
  (`local_runtime_unreachable`);
- the endpoint returns a non-2xx status (`local_runtime_http_error`);
- the response body exceeds `max_response_chars` (`local_output_too_large`);
- the response is not valid/parseable single-object JSON (`local_output_unparseable`);
- the parsed output contains unsafe command/tool/mutation keys (`local_unsafe_output`);
- the output fails `ExtractionResult` schema validation (`local_output_schema_invalid`).

## Example local configuration (placeholders only — never commit secrets)

```
ORDERPILOT_AI_LOCAL_MODEL_ENABLED=false
ORDERPILOT_AI_LOCAL_MODEL_ENDPOINT=http://localhost:11434
ORDERPILOT_AI_LOCAL_MODEL_NAME=<local-model-name>
ORDERPILOT_AI_LOCAL_MODEL_TIMEOUT_SECONDS=30
# optional:
# ORDERPILOT_AI_LOCAL_MODEL_TEMPERATURE=0.0
# ORDERPILOT_AI_LOCAL_MODEL_MAX_PROMPT_CHARS=20000
# ORDERPILOT_AI_LOCAL_MODEL_MAX_RESPONSE_CHARS=200000
```

Do not commit `.env` secrets. Local Ollama endpoints are unauthenticated by design; if a deployment
fronts the runtime with auth, supply that out of band via the injected transport, never via committed
config.

## Tests

`apps/ai-worker/tests/extraction/test_local_ollama_provider.py` — all use a deterministic in-process
fake transport (no real network, no installed model): disabled/missing-config fail-closed, successful
envelope/direct/fenced/noisy parsing, invalid/multiple/oversized/schema-invalid/unsafe output fail
closed, `advisory_only=false` overridden, prompt-injection-as-risk, timeout/non-2xx fail closed, and
secret/credential non-leakage (no realistic secret prefixes used).

## Known limitations

- The local model is **untrusted** and advisory only; confidence ≠ business approval.
- Provider selection is **not yet wired** into job orchestration (deferred to a later slice); use the
  provider directly or construct it with config + transport. It is disabled/fail-closed by default.
- No real model is exercised in CI; production needs a running local runtime and an injected transport
  (`build_urllib_transport()` or a deployment-specific client).
- OCR/PDF remains text-input based; semantic SKU/fitment matching is advisory — deterministic Core API
  intelligence still owns real resolution.
