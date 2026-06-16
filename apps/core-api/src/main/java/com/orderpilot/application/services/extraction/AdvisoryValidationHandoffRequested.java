package com.orderpilot.application.services.extraction;

import java.util.UUID;

/**
 * OP-CAP-13B — transaction-bound event signalling that a persisted AI-worker advisory extraction
 * result is structurally usable and should be handed off into deterministic validation.
 *
 * <p>Published by {@code AiWorkerResultIntakeService} inside the intake transaction and consumed by an
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so the handoff runs only after the advisory
 * result is durably committed. Carries identifiers only — never the advisory payload, document text,
 * or secrets. {@code tenantId} is the trusted server-resolved tenant (never taken from a request body).
 */
public record AdvisoryValidationHandoffRequested(
    UUID tenantId, UUID extractionResultId, UUID jobId, String workerStatus) {}
