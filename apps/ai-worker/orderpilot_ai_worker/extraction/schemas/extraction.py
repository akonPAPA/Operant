from pydantic import BaseModel, Field


class SourceEvidence(BaseModel):
    evidence_type: str
    snippet: str | None = None
    start_offset: int | None = None
    end_offset: int | None = None
    page_number: int | None = None
    message_id: str | None = None
    channel_event_id: str | None = None


class ExtractedField(BaseModel):
    field_name: str
    raw_value: str | None = None
    normalized_value: str | None = None
    value_type: str = "STRING"
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: SourceEvidence | None = None


class ExtractedLineItem(BaseModel):
    line_number: int
    raw_sku: str | None = None
    raw_alias: str | None = None
    raw_description: str | None = None
    raw_quantity: str | None = None
    raw_uom: str | None = None
    requested_date: str | None = None
    ship_to_location_hint: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: SourceEvidence | None = None


class AiSuggestion(BaseModel):
    suggestion_type: str
    suggestion: dict
    confidence: float = Field(ge=0.0, le=1.0)


class ExtractionResult(BaseModel):
    detected_intent: str
    document_type: str
    overall_confidence: float = Field(ge=0.0, le=1.0)
    extraction_method: str = "mock"
    provider_name: str = "mock-semantic"
    model_name: str = "mock-rule-based"
    prompt_version: str = "stage4.prompt.v1"
    schema_version: str = "stage4.v1"
    source_channel_context: str | None = None
    customer_hints: list[str] = Field(default_factory=list)
    validation_status: str = "needs_review"
    fields: list[ExtractedField] = Field(default_factory=list)
    line_items: list[ExtractedLineItem] = Field(default_factory=list)
    evidence: list[SourceEvidence] = Field(default_factory=list)
    suggestions: list[AiSuggestion] = Field(default_factory=list)
    advisory_only: bool = True
