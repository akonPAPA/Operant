from pydantic import BaseModel, Field


class ExtractedLineItem(BaseModel):
    raw_text: str
    quantity: float | None = None
    sku: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)


class ExtractionResult(BaseModel):
    document_id: str
    summary: str
    confidence: float = Field(ge=0.0, le=1.0)
    line_items: list[ExtractedLineItem] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    advisory_only: bool = True