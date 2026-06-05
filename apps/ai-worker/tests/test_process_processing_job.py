"""Tests for advisory processing-job task behavior."""

from orderpilot_ai_worker.tasks.process_processing_job import (
    ProcessingJobPayload,
    process_processing_job,
)


def test_processing_job_placeholder_is_advisory() -> None:
    """Processing job placeholder accepts known advisory job types."""
    result = process_processing_job(
        ProcessingJobPayload(
            job_id="job-1",
            job_type="DOCUMENT_PROCESSING",
            target_type="INBOUND_DOCUMENT",
            target_id="doc-1",
        )
    )

    assert result.accepted is True
    assert result.advisory_only is True
