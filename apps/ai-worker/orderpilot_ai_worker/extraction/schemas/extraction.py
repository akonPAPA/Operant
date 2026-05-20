from pydantic import BaseModel, Field


class SourceEvidence(BaseModel):
    evidence_type: str
    snippet: str | None = None
    start_offset: int | None = None
    end_offset: int | None = None


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
    raw_description: str | None = None
    raw_quantity: str | None = None
    raw_uom: str | None = None
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
    fields: list[ExtractedField] = Field(default_factory=list)
    line_items: list[ExtractedLineItem] = Field(default_factory=list)
    evidence: list[SourceEvidence] = Field(default_factory=list)
    suggestions: list[AiSuggestion] = Field(default_factory=list)
    advisory_only: bool = True