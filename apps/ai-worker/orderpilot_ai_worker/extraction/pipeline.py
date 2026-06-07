"""Advisory understanding pipeline (OP-CAP-07B).

Conceptual flow:

    raw text/message/document
      -> input sanitizer + bounds guard
      -> prompt-injection scan / hostile-instruction tagging
      -> provider extraction (deterministic rule-based by default)
      -> schema validation (advisory-only enforcement)
      -> confidence scoring + evidence (provided by the provider)
      -> safe structured ExtractionResult (advisory only)

The pipeline never validates against the real product catalog, never checks inventory/price, never
approves anything, and has no path to create quotes/orders or trigger ERP/connector writes. Customer
content is treated as hostile input: prompt-injection phrases are tagged as content and force human
review; they are never executed. Invalid provider output can never pass silently — it becomes a
controlled ``validation_status="failed"`` result carrying safe metadata only.
"""

import uuid
from typing import Optional

from pydantic import BaseModel, Field

from orderpilot_ai_worker.extraction.providers.rule_based import RuleBasedExtractionProvider
from orderpilot_ai_worker.extraction.providers.semantic_extraction import (
    SemanticExtractionProvider,
)
from orderpilot_ai_worker.extraction.schemas.extraction import (
    AiSuggestion,
    ExtractionResult,
    ModelMetadata,
    RiskSignals,
)
from orderpilot_ai_worker.extraction.security.output_sanitizer import sanitize_text
from orderpilot_ai_worker.extraction.security.prompt_injection import detect_prompt_injection

# Bound the inbound text the pipeline will process so a hostile/huge payload cannot exhaust memory
# or end up echoed wholesale into results. Snippets are bounded again inside the provider.
MAX_INPUT_CHARS = 20_000

# Advisory model provenance for pipeline-level controlled-failure results (no provider ran, or the
# provider's output was rejected). Mirrors the default rule-based provider identity.
_FAILED_MODEL_METADATA = ModelMetadata(
    provider="rule-based-understanding",
    model="rule-based-v1",
    prompt_version="op-cap-12a.prompt.v1",
    schema_version="stage4.v1",
)

# Hostile-phrase fragments that indicate an attempt to exfiltrate system/customer data, used only to
# set an advisory risk flag. They are never executed and never alter behavior beyond forcing review.
_EXFIL_MARKERS = ("export", "dump", "leak", "reveal", "system prompt", "customer data", "secret")

# Normalize caller source types to a stable advisory document_type. Unknown types are accepted but
# tagged, never crashed on.
_SOURCE_TYPE_TO_DOCUMENT_TYPE = {
    "channel_message": "message",
    "telegram": "message",
    "message": "message",
    "email": "email",
    "pdf": "pdf_text",
    "pdf_text": "pdf_text",
    "ocr": "pdf_text",
    "excel": "spreadsheet_text",
    "csv": "spreadsheet_text",
    "spreadsheet": "spreadsheet_text",
    "spreadsheet_text": "spreadsheet_text",
    "document": "document_text",
    "document_text": "document_text",
    "api": "document_text",
    "inbound_document": "document_text",
}


class ExtractionInput(BaseModel):  # pylint: disable=too-few-public-methods
    """Input to the advisory understanding pipeline.

    ``tenant_id`` is optional here: the worker is advisory and tenant authority lives in Core API,
    which always re-establishes tenant scope when it consumes this advisory output.
    """

    source_type: str
    source_id: str
    raw_text: Optional[str] = None
    tenant_id: Optional[str] = None
    source_metadata: dict = Field(default_factory=dict)
    mode: str = "rule_based"


class SemanticExtractionPipeline:  # pylint: disable=too-few-public-methods
    """Orchestrates sanitize -> injection scan -> provider -> schema validation -> safe result."""

    def __init__(self, provider: SemanticExtractionProvider | None = None) -> None:
        self._provider = provider or RuleBasedExtractionProvider()

    def run(self, payload: ExtractionInput) -> ExtractionResult:
        """Produce a schema-valid, advisory-only extraction result for the supplied input."""
        extraction_id = uuid.uuid4().hex
        document_type, source_warnings = _document_type_for(payload.source_type)

        raw = payload.raw_text or ""
        if not raw.strip():
            return _failed_result(extraction_id, payload, document_type, "empty_input")

        warnings = list(source_warnings)
        text = raw
        if len(text) > MAX_INPUT_CHARS:
            text = text[:MAX_INPUT_CHARS]
            warnings.append("input_truncated")

        # Defense-in-depth: sanitize and scan on the pipeline side too (the provider also sanitizes).
        safe_text = sanitize_text(text) or ""
        injection_signals = detect_prompt_injection(safe_text)

        source_channel = payload.source_metadata.get("source_channel")
        try:
            result = self._provider.extract(safe_text, source_channel_context=source_channel)
        except Exception:  # noqa: BLE001 - never leak provider internals/customer text into errors
            return _failed_result(extraction_id, payload, document_type, "provider_error")

        if not isinstance(result, ExtractionResult) or result.advisory_only is not True:
            return _failed_result(extraction_id, payload, document_type, "invalid_provider_output")

        result.extraction_id = extraction_id
        result.source_type = payload.source_type
        result.source_id = payload.source_id
        result.document_type = document_type
        result.warnings = warnings + list(result.warnings)
        fixture_key = payload.source_metadata.get("fixture_source_key")
        if isinstance(fixture_key, str) and fixture_key.strip():
            result.fixture_source_key = fixture_key.strip()[:120]

        if injection_signals:
            _apply_injection_tagging(result, injection_signals)

        return result


def run_extraction(payload: ExtractionInput, provider: SemanticExtractionProvider | None = None) -> ExtractionResult:
    """Convenience entry point: run the default (or supplied) pipeline once."""
    return SemanticExtractionPipeline(provider=provider).run(payload)


def _document_type_for(source_type: str) -> tuple[str, list[str]]:
    key = (source_type or "").strip().lower()
    mapped = _SOURCE_TYPE_TO_DOCUMENT_TYPE.get(key)
    if mapped is None:
        return "document_text", ["unsupported_source_type"]
    return mapped, []


def _apply_injection_tagging(result: ExtractionResult, signals: list[str]) -> None:
    """Record hostile-instruction signals as content tags and force human review.

    This NEVER executes the instruction; it only flags the document. Confidence is reduced and the
    result is routed to review so an operator (via Core API) decides what to do with the content.
    """
    result.prompt_injection_signals = signals
    result.warnings = list(result.warnings) + ["prompt_injection_detected"]
    result.validation_status = "needs_review"
    result.overall_confidence = min(result.overall_confidence, 0.25)
    if result.document_confidence is not None:
        result.document_confidence = min(result.document_confidence, 0.25)
    else:
        result.document_confidence = result.overall_confidence
    # Reflect the hostile content as advisory risk flags. These force review; they never become a
    # command — the worker has no mutation path and the schema has no executable action surface.
    risk = result.risk_signals or RiskSignals()
    risk.prompt_injection_suspected = True
    risk.unsafe_instruction = True
    risk.low_confidence = True
    if any(marker in s for s in signals for marker in _EXFIL_MARKERS):
        risk.possible_data_exfiltration = True
    for token in ("prompt_injection_suspected", "unsafe_instruction"):
        if token not in risk.details:
            risk.details.append(token)
    result.risk_signals = risk
    result.suggestions = list(result.suggestions) + [
        AiSuggestion(
            suggestion_type="PROMPT_INJECTION_SIGNAL",
            # Bounded, known phrase list only — no raw customer payload is echoed here.
            suggestion={"detected_phrases": signals[:20], "handling": "TAGGED_AS_CONTENT_FORCED_REVIEW"},
            confidence=0.9,
        )
    ]


def _failed_result(
    extraction_id: str, payload: ExtractionInput, document_type: str, reason: str
) -> ExtractionResult:
    """Controlled failure: advisory-only, no business state, safe metadata only."""
    return ExtractionResult(
        detected_intent="unknown",
        document_type=document_type,
        overall_confidence=0.0,
        document_confidence=0.0,
        extraction_method="rule_based",
        provider_name="rule-based-understanding",
        validation_status="failed",
        extraction_id=extraction_id,
        source_type=payload.source_type,
        source_id=payload.source_id,
        warnings=[reason],
        advisory_only=True,
        language="en",
        risk_signals=RiskSignals(low_confidence=True, details=[reason]),
        operator_summary=f"Extraction failed ({reason}); advisory only — no business state.",
        model_metadata=_FAILED_MODEL_METADATA,
    )
