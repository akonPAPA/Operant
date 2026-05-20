from abc import ABC, abstractmethod


class TextExtractionProvider(ABC):
    @abstractmethod
    def extract_text(self, payload: bytes) -> str:
        """Extract text from a document payload without mutating business data."""


class MockTextExtractionProvider(TextExtractionProvider):
    def extract_text(self, payload: bytes) -> str:
        return payload.decode("utf-8", errors="replace")