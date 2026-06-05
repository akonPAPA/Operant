"""Document text extraction provider contracts."""

from abc import ABC, abstractmethod


class TextExtractionProvider(ABC):  # pylint: disable=too-few-public-methods
    """Provider boundary for extracting text from document bytes."""

    @abstractmethod
    def extract_text(self, payload: bytes) -> str:
        """Extract text from a document payload without mutating business data."""


class MockTextExtractionProvider(TextExtractionProvider):  # pylint: disable=too-few-public-methods
    """UTF-8 mock extractor for local advisory document processing."""

    def extract_text(self, payload: bytes) -> str:
        """Decode payload bytes as replacement-safe UTF-8."""
        return payload.decode("utf-8", errors="replace")
