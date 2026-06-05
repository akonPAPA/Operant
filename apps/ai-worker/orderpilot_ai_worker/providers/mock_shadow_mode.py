"""Mock shadow-mode provider for advisory predictions."""

from orderpilot_ai_worker.schemas.shadow_mode import ShadowModeAdvisoryPayload


class MockShadowModeProvider:  # pylint: disable=too-few-public-methods
    """Build advisory-only shadow-mode payloads without external provider calls."""

    def predict(
        self,
        source_type: str,
        source_id: str,
        prediction_type: str,
        text: str,
    ) -> ShadowModeAdvisoryPayload:
        """Return a deterministic advisory shadow-mode payload."""
        return ShadowModeAdvisoryPayload(
            source_type=source_type,
            source_id=source_id,
            prediction_type=prediction_type,
            prediction_payload={
                "summary": text[:160],
                "suggested_action": "human_review",
                "warnings": ["mock_shadow_mode_output_not_authoritative"],
            },
            confidence_score=0.55,
        )
