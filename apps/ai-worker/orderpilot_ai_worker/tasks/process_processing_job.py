"""Advisory processing-job task contract."""

from pydantic import BaseModel

from orderpilot_ai_worker.security.ai_safety import AI_ADVISORY_ONLY_NOTICE, assert_advisory_task


class ProcessingJobPayload(BaseModel):  # pylint: disable=too-few-public-methods
    """Input payload for a local advisory processing job."""

    job_id: str
    job_type: str
    target_type: str
    target_id: str


class ProcessingJobResult(BaseModel):  # pylint: disable=too-few-public-methods
    """Result returned by the advisory processing-job task."""

    job_id: str
    accepted: bool
    advisory_only: bool = True
    message: str


def process_processing_job(payload: ProcessingJobPayload) -> ProcessingJobResult:
    """Accept only known advisory processing job types."""
    assert_advisory_task("process_inbound_document")
    accepted_job_types = {
        "DOCUMENT_PROCESSING",
        "MESSAGE_PROCESSING",
        "ATTACHMENT_PROCESSING",
    }
    return ProcessingJobResult(
        job_id=payload.job_id,
        accepted=payload.job_type in accepted_job_types,
        message=AI_ADVISORY_ONLY_NOTICE,
    )
