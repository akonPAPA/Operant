"""Text extraction provider contracts for advisory extraction jobs."""

from abc import ABC, abstractmethod


class TextExtractionProvider(ABC):  # pylint: disable=too-few-public-methods
    """Provider boundary for extracting text from channel payloads."""

    @abstractmethod
    def extract_text(self, payload: dict) -> str:
        """Return text only. Do not mutate business data."""


class MockTextExtractionProvider(TextExtractionProvider):  # pylint: disable=too-few-public-methods
    """Deterministic text extractor used by local advisory worker tests."""

    def extract_text(self, payload: dict) -> str:
        """Return text from a simple in-memory payload."""
        return str(
            payload.get("text")
            or payload.get("metadata")
            or "Mock extracted text: Need 10 EA SKU-001"
        )
