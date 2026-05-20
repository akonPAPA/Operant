from orderpilot_ai_worker.providers.mock_shadow_mode import MockShadowModeProvider


def test_mock_shadow_mode_payload_is_advisory_and_mock_only() -> None:
    payload = MockShadowModeProvider().predict(
        source_type="INBOUND_DOCUMENT",
        source_id="document-1",
        prediction_type="EXTRACTION",
        text="Need 5 EA SKU-1",
    )

    assert payload.provider_mode == "MOCK_ONLY"
    assert payload.advisory_only is True
    assert payload.external_writes_enabled is False
    assert payload.confidence_score == 0.55
    assert payload.prediction_payload["suggested_action"] == "human_review"


def test_mock_shadow_mode_payload_has_no_provider_secret_requirement() -> None:
    payload = MockShadowModeProvider().predict(
        source_type="VALIDATION_CASE",
        source_id="validation-1",
        prediction_type="VALIDATION",
        text="Validate substitute candidate",
    )

    assert "api_key" not in payload.model_dump_json().lower()
    assert "secret" not in payload.model_dump_json().lower()
