from pydantic import BaseModel

from orderpilot_ai_worker.extraction.providers.semantic_extraction import MockSemanticExtractionProvider
from orderpilot_ai_worker.extraction.providers.text_extraction import MockTextExtractionProvider
from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult


class ExtractionJobPayload(BaseModel):
    job_id: str
    source_type: str
    source_id: str
    text: str | None = None
    metadata: dict = {}


def process_extraction_job(payload: ExtractionJobPayload) -> ExtractionResult:
    text = MockTextExtractionProvider().extract_text({"text": payload.text, "metadata": payload.metadata})
    return MockSemanticExtractionProvider().extract(text)