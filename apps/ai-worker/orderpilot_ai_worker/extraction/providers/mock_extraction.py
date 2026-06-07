"""Deterministic mock extraction provider with OP-CAP-11I fixture recognition (OP-CAP-12A).

This is the canonical deterministic provider for tests/local CI. It reuses the rule-based
understanding extractor unchanged (no special-cased "right answers") and additionally tags the
recognized OP-CAP-11I scripted-scenario code as advisory provenance on ``fixture_source_key``.

Recognition is provenance only: it never alters extraction logic, never validates business data, and
never lets a recognized fixture bypass deterministic Core API validation.
"""

from typing import List, Tuple

from orderpilot_ai_worker.extraction.providers.rule_based import RuleBasedExtractionProvider
from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult
from orderpilot_ai_worker.extraction.security.prompt_injection import detect_prompt_injection

PROVIDER_NAME = "mock-extraction-12a"

# (required_substrings, scenario_code). First all-substrings match wins; matching is order-sensitive
# and case-insensitive. Codes mirror OP-CAP-11H/11I scenario codes exactly.
_FIXTURE_MARKERS: Tuple[Tuple[Tuple[str, ...], str], ...] = (
    (("camry pads",), "PDF_PO_EXCEPTION"),
    (("demo-po",), "PDF_PO_EXCEPTION"),
    (("pad-sub-adv", "discount"), "DISCOUNT_MARGIN_GUARDRAIL"),
    (("pad-oe-04465", "camry"), "TELEGRAM_RFQ_SUBSTITUTION"),
)


class MockExtractionProvider(RuleBasedExtractionProvider):  # pylint: disable=too-few-public-methods
    """Rule-based extractor that also tags the recognized 11I scripted-scenario code (advisory)."""

    def extract(self, text: str, source_channel_context: str | None = None) -> ExtractionResult:
        result = super().extract(text, source_channel_context=source_channel_context)
        code = recognize_fixture_scenario(text or "")
        if code and not result.fixture_source_key:
            result.fixture_source_key = code
        return result


def recognize_fixture_scenario(text: str) -> str | None:
    """Return the OP-CAP-11I scenario code recognized from input text, else None. Provenance only."""
    lowered = (text or "").lower()
    # A hostile/injection input maps to the unsafe-input scenario regardless of business tokens.
    if detect_prompt_injection(lowered):
        return "BAD_AI_OUTPUT_REJECTED"
    for markers, code in _FIXTURE_MARKERS:
        if all(marker in lowered for marker in markers):
            return code
    return None


def known_scenario_codes() -> List[str]:
    """Distinct scenario codes this mock can recognize (for documentation/tests)."""
    codes = ["BAD_AI_OUTPUT_REJECTED"] + [code for _, code in _FIXTURE_MARKERS]
    seen: set[str] = set()
    return [c for c in codes if not (c in seen or seen.add(c))]
