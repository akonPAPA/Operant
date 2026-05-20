from orderpilot_ai_worker.providers.llm_provider import MockLLMProvider
from orderpilot_ai_worker.schemas.extraction_result import ExtractionResult
from orderpilot_ai_worker.security.ai_safety import AI_ADVISORY_ONLY_NOTICE, assert_advisory_task


def process_inbound_document(document_id: str, text: str) -> ExtractionResult:
    assert_advisory_task("process_inbound_document")
    provider = MockLLMProvider()
    raw = provider.extract_business_intent(text)
    raw["document_id"] = document_id
    raw["advisory_only"] = True
    raw.setdefault("warnings", []).append(AI_ADVISORY_ONLY_NOTICE)
    return ExtractionResult.model_validate(raw)