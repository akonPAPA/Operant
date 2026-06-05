"""Output safety helpers for advisory extraction results."""

from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult


def sanitize_text(value: str | None) -> str | None:
    """Remove active-script markers from hostile customer-provided text."""
    if value is None:
        return None
    return (
        value.replace("<script", "&lt;script")
        .replace("</script>", "&lt;/script&gt;")
        .replace("javascript:", "")
    )


def validate_result(result: ExtractionResult) -> ExtractionResult:
    """Reject extraction output that attempts to become authoritative."""
    if not result.advisory_only:
        raise ValueError("Extraction output must remain advisory")
    return result
