from pydantic import BaseModel

from orderpilot_ai_worker.security.ai_safety import AI_ADVISORY_ONLY_NOTICE, assert_advisory_task


class ProcessingJobPayload(BaseModel):
    job_id: str
    job_type: str
    target_type: str
    target_id: str


class ProcessingJobResult(BaseModel):
    job_id: str
    accepted: bool
    advisory_only: bool = True
    message: str


def process_processing_job(payload: ProcessingJobPayload) -> ProcessingJobResult:
    assert_advisory_task("process_inbound_document")
    return ProcessingJobResult(
        job_id=payload.job_id,
        accepted=payload.job_type in {"DOCUMENT_PROCESSING", "MESSAGE_PROCESSING", "ATTACHMENT_PROCESSING"},
        message=AI_ADVISORY_ONLY_NOTICE,
    )