# AI Understanding Layer (OP-CAP-12A)

Status: foundation. This document describes the **advisory AI understanding layer** that turns messy
customer input into structured, evidence-linked, confidence-scored business suggestions — without
giving AI any authority over business writes.

It complements (does not replace) [`AI_GOVERNANCE.md`](../AI_GOVERNANCE.md) and
[`VALIDATION_ENGINE.md`](../VALIDATION_ENGINE.md). Read those for the platform-wide safety model and
the deterministic validation authority.

## Purpose

Make OrderPilot visibly and technically an AI-powered transaction-intelligence product, not just a
bot/workflow/reporting dashboard. The understanding layer reads raw text from email, PDF/Excel text,
Telegram/WhatsApp-ready channels, and APIs, and produces a strict, advisory `ExtractionResult` that
deterministic Core API validation then turns into validated quotes, draft orders, substitution
suggestions, and pilot evidence.

## Core safety rule (unchanged)

> AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records.

AI output is **untrusted input**. It can never directly create or approve quotes, orders, inventory
changes, price/discount/margin changes, customer updates, ERP/1C writes, or connector commands. The
worker has **no mutation path and no tool surface**; the output schema has **no executable action
field**. High-risk input only *forces human review* — it can never become a command.

## Architecture

```
raw text / message / document text
  -> AI input sanitizer            (strip active-script markers, bound size)
  -> prompt-injection / unsafe-content guard   (tag hostile content; never obey it)
  -> LLM provider abstraction      (SemanticExtractionProvider)
       - MockExtractionProvider / RuleBasedExtractionProvider  (deterministic, default, CI)
       - ConfigurableLlmExtractionProvider                     (disabled-by-default seam)
  -> structured ExtractionResult   (strict pydantic schema)
  -> schema validation             (advisory_only forced; malformed output rejected)
  -> confidence + evidence scoring
  -> safe advisory result          (bounded, no raw payload, no secrets)
  -> [Core API] deterministic validation handoff  (SKU/customer/price/stock/margin authority)
  -> human review / pilot evidence / scenario demo
```

The understanding layer **never** validates against the real catalog, never checks inventory/price,
never approves anything, and has no path to create quotes/orders or trigger ERP/connector writes.

Code locations (worker, `apps/ai-worker`):

- Pipeline: `orderpilot_ai_worker/extraction/pipeline.py`
- Schema: `orderpilot_ai_worker/extraction/schemas/extraction.py`
- Providers: `orderpilot_ai_worker/extraction/providers/`
  - `rule_based.py` — deterministic understanding extractor (default)
  - `mock_extraction.py` — `MockExtractionProvider` (rule-based + 11I fixture provenance)
  - `configurable_llm.py` — `ConfigurableLlmExtractionProvider` (fail-closed real-provider seam)
  - `semantic_extraction.py` — `SemanticExtractionProvider` boundary + legacy mock
- Security: `extraction/security/prompt_injection.py`, `extraction/security/output_sanitizer.py`
- Job/handoff contract to Core API: `orderpilot_ai_worker/jobs/` (OP-CAP-07C)

Core API remains the authority for validation, policy, audit, commands, tenant isolation, and all
business writes. It re-validates every advisory result on receipt.

## Provider abstraction

All providers implement `SemanticExtractionProvider.extract(text, source_channel_context) ->
ExtractionResult`. They support transaction extraction from text, optional summarization
(`operator_summary`), and unsafe-input signaling (`risk_signals` / `prompt_injection_signals`).

| Provider | Network | Default | Purpose |
|---|---|---|---|
| `RuleBasedExtractionProvider` | none | yes | Deterministic understanding extractor |
| `MockExtractionProvider` | none | tests/CI | Rule-based + OP-CAP-11I scenario provenance |
| `ConfigurableLlmExtractionProvider` | none in CI | **disabled** | Future real-LLM seam, fails closed |
| `LocalOllamaExtractionProvider` | none in CI | **disabled** | Local open-source model runtime (OP-CAP-12B), fails closed |

### Local open-source model runtime (OP-CAP-12B)

`LocalOllamaExtractionProvider` adds a **local open-source model runtime** seam — it calls a locally
running model through an Ollama-compatible local HTTP endpoint (e.g. `http://localhost:11434`). This
is **not** a paid API and **not** OpenAI/Anthropic/Azure. It is disabled and fail-closed by default,
uses an **injected transport** (tests never hit the network), and treats the local model as untrusted:
output is bounded, parsed, scanned for unsafe/command-like keys, forced `advisory_only=True`, and
re-validated through the same `ExtractionResult` schema. See
[`LOCAL_MODEL_RUNTIME.md`](LOCAL_MODEL_RUNTIME.md) for configuration, request/response handling, and
fail-closed cases.

### Provider-mode selection (OP-CAP-12C)

Provider selection is one fail-closed point — `jobs/provider_factory.build_extraction_provider(mode)`
— used by `process_ai_extraction_job`. `ProviderMode` (`jobs/models.py`) is
`RULE_BASED` (default), `MOCK_SEMANTIC`, and `LOCAL_OLLAMA`; `FUTURE_SEMANTIC` stays a non-runnable
placeholder rejected by the security envelope. The worker default comes from
`provider_mode_from_env()` reading `ORDERPILOT_AI_PROVIDER_MODE`, falling back to `RULE_BASED` for
unknown/blank — it never silently upgrades to a model-backed mode.

`LOCAL_OLLAMA` is **explicit opt-in and double-gated**: the mode must be chosen *and*
`LocalModelConfig` must be ready (`ORDERPILOT_AI_LOCAL_MODEL_ENABLED=true` + endpoint + model) before
the factory builds the urllib transport. Otherwise the provider is constructed with no transport and
fails closed (`ProviderDisabledError`) with no network client built; the pipeline turns that into a
controlled `FAILED`/`provider_error` advisory result — no business state is created. An unknown mode
raises a typed `ProviderResolutionError` rather than reaching the network. No paid API or key is
involved. This wiring does **not** change Core API deterministic-validation authority. See
[`LOCAL_MODEL_RUNTIME.md`](LOCAL_MODEL_RUNTIME.md#provider-mode-wiring-op-cap-12c).

### Mock vs real provider behavior

- **Mock / rule-based:** fully deterministic, no network, no key. Recognizes OP-CAP-11I scripted
  scenarios and tags `fixture_source_key` as advisory provenance only (it never changes extraction
  logic or bypasses validation).
- **Configurable LLM (real seam):** **disabled by default and fails closed.** It runs only when
  explicitly enabled with a provider, model, and API key (`OP_AI_LLM_ENABLED` /`OP_AI_LLM_PROVIDER`
  /`OP_AI_LLM_MODEL` /`OP_AI_LLM_API_KEY`) **and** an injected `transport` callable. The worker never
  constructs a network client itself; the model request is delegated out of band. The API key is
  never logged, never serialized, and never placed in `repr`/prompts (`api_key` uses `repr=False`;
  `safe_metadata()` omits it). Even a real model's output is treated as untrusted: it is sanitized,
  forced `advisory_only=True`, and re-validated through the same schema; malformed output becomes a
  controlled pipeline failure.

## Extraction schema (advisory)

`ExtractionResult` is strict (pydantic, confidence bounded `0.0–1.0`). OP-CAP-12A adds richer
top-level structure on top of the existing flat fields (all additions optional/defaulted, so existing
producers/consumers keep working):

- `detected_intent` — `RFQ` / `purchase_order` / `availability_inquiry` / `price_inquiry` /
  `order_status_inquiry` / `substitute_request` / `unknown`
- `language` — deterministic hint (`ru` if Cyrillic present, else `en`)
- `customer` — `CustomerContext{ raw_name, contact_handle, channel, confidence, evidence }`
- `line_items[]` — `ExtractedLineItem{ raw_sku, raw_alias, raw_oem_reference, raw_description,
  raw_quantity, raw_uom, requested_date, ship_to_location_hint, vehicle_context, ambiguous,
  unsupported_uom, confidence, evidence }`
- `commercial_context` — `{ requested_discount, urgency, wholesale_retail_hint,
  delivery_location_hint }`
- `risk_signals` — `{ prompt_injection_suspected, ambiguous_product, missing_quantity,
  low_confidence, unsafe_instruction, possible_data_exfiltration, unsupported_uom, details[] }`
- `operator_summary` — one bounded, structured, human-readable line (tokens/counts only)
- `model_metadata` — `{ provider, model, prompt_version, schema_version }`
- `overall_confidence`, plus legacy `fields[]`, `suggestions[]`, `evidence[]`, `warnings[]`,
  `prompt_injection_signals[]`, `validation_status`, `advisory_only`, `fixture_source_key`

### Confidence + evidence model

Each important extracted value carries a `confidence` and a `SourceEvidence` pointer
(`evidence_type`, bounded `snippet`, `start_offset`/`end_offset`, optional `page_number`,
`message_id`, `channel_event_id`). `overall_confidence` aggregates field coverage and intent.
`fixture_source_key` records the recognized 11I scenario when applicable. Snippets, descriptions,
summaries, and total payload are all size-bounded; the worker never echoes the full raw payload and
never carries secrets. **Confidence is a routing hint, not a business approval.**

## Prompt-injection / unsafe-content handling

The guard (`detect_prompt_injection`) recognizes phrases such as "ignore previous instructions",
"reveal system prompt", "export all customer data", "write to ERP", "approve this order",
"disable validation", "bypass manager approval", etc. When hostile content is present:

- it is recorded as **content signals only** in `prompt_injection_signals` (bounded, known-phrase
  list — no raw payload echo);
- `risk_signals.prompt_injection_suspected` / `unsafe_instruction` are set (and
  `possible_data_exfiltration` for exfiltration phrasing);
- `validation_status` is forced to `needs_review` and confidence is capped (≤ 0.25);
- safe business fields are still extracted if present, but the document cannot be auto-ready;
- the instruction is **never executed** — there is no action surface to execute it on.

## How 11I scripted fixtures are used

`MockExtractionProvider` recognizes the OP-CAP-11I scripted-scenario codes from input content and
tags `fixture_source_key` (provenance only):

- `TELEGRAM_RFQ_SUBSTITUTION` — RFQ intent; Camry / brake pads / quantity / location extracted.
- `PDF_PO_EXCEPTION` — multiple line items; ambiguous SKU line and unsupported-UOM line flagged.
- `DISCOUNT_MARGIN_GUARDRAIL` — requested discount extracted; a `REQUIRES_MARGIN_VALIDATION`
  suggestion flags that Core API's deterministic margin guardrail must run (the worker computes no
  margin).
- `BAD_AI_OUTPUT_REJECTED` — prompt-injection input becomes risk + review, never a command.

Tests live in `apps/ai-worker/tests/extraction/test_understanding_schema_12a.py` and
`test_configurable_provider.py`. Test inputs are plain text compatible with the 11I scenario codes
(not byte-for-byte copies of fixture files).

## How output flows to validation

The advisory `ExtractionResult` is handed to Core API (OP-CAP-07C job/handoff contract) as **advisory
data**. Core API re-establishes tenant scope and runs deterministic validation: SKU/alias resolution,
customer/account checks, price/stock/margin guardrails, and substitution rules. AI confidence alone
approves nothing; the deterministic engine and human approval remain the authority.

## Safety boundary: AI cannot write business data

- No AI direct writes to quote/order/inventory/customer/price/discount/margin/business tables.
- No ERP/1C/connector writes; no autonomous quote/order approval; no AI tool-use.
- No new public mutation endpoint for AI actions.
- No real provider keys committed; no production model call required for CI; no external network in
  tests.
- No raw customer-sensitive payload in advisory output; no unsafe HTML; size-bounded throughout.

## Known limitations (honest)

- The mock/rule-based provider is **deterministic and not a production model**.
- The real provider is **disabled until secrets/config are supplied**, and even then needs an
  injected transport; there is no live model call in this slice.
- OCR/PDF extraction is still **text-fixture-based** unless existing OCR is wired; no binary PDF is
  parsed here.
- Semantic SKU/fitment matching is advisory only; the **deterministic product intelligence layer in
  Core API** still owns real resolution. `raw_sku`/`raw_oem_reference` are candidate hints.
- Language detection is a deterministic heuristic (Cyrillic vs not), not a full classifier.
- **AI confidence ≠ business approval.** Full production AI is **not** complete.

## Next steps for production LLM/OCR

1. Provider selection for `LocalOllamaExtractionProvider` is wired (OP-CAP-12C):
   `ProviderMode.LOCAL_OLLAMA` via `jobs/provider_factory.build_extraction_provider`, opt-in and
   fail-closed. `ConfigurableLlmExtractionProvider` remains an unwired fail-closed seam; wiring it (if
   ever) would follow the same factory pattern with config + injected transport.
2. For a paid/hosted provider, implement a concrete `transport` for
   `ConfigurableLlmExtractionProvider` (HTTPS + service-managed key, out of band) behind its
   fail-closed config.
3. Add real OCR/PDF text extraction feeding the same pipeline input.
4. Strengthen schema validation/repair for real-model output and add provider/version telemetry.
5. Expand deterministic SKU/alias/fitment intelligence in Core API for AI candidate resolution.
6. Add rate limits/quotas and audit around any controlled processing-job trigger.
