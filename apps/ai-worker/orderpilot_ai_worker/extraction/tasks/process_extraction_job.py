"""Advisory semantic extraction task entry point."""

from pydantic import BaseModel, Field

from orderpilot_ai_worker.extraction.providers.semantic_extraction import (
    MockSemanticExtractionProvider,
)
from orderpilot_ai_worker.extraction.providers.text_extraction import MockTextExtractionProvider
from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult


class ExtractionJobPayload(BaseModel):  # pylint: disable=too-few-public-methods
    """Input payload for an advisory extraction job."""

    job_id: str
    source_type: str
    source_id: str
    text: str | None = None
    metadata: dict = Field(default_factory=dict)


def process_extraction_job(payload: ExtractionJobPayload) -> ExtractionResult:
    """Run deterministic advisory extraction for the supplied payload."""
    text = MockTextExtractionProvider().extract_text(
        {"text": payload.text, "metadata": payload.metadata}
    )
    source_channel = payload.metadata.get("source_channel")
    return MockSemanticExtractionProvider().extract(text, source_channel_context=source_channel)
