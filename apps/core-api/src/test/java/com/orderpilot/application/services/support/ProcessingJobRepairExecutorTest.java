package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-54 — the bounded processing-job status-repair executor. Proves the full gate (approval + target +
 * deterministic validation), the hard mutation boundary (only the one processing_job row and the request
 * execution metadata change), idempotent replay, cross-tenant denial, and complete audit — all against a
 * real database.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProcessingJobRepairExecutorTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");
  private static final Duration THRESHOLD = ProcessingJobStatusRepairValidator.STALENESS_THRESHOLD;

  @Autowired private DataRepairRequestRepository requestRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private UUID tenantId;

  @BeforeEach
  void setUp() {
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private DataRepairService dataRepairServiceAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new DataRepairService(
        requestRepository, new AuditEventService(auditEventRepository, clock), new ObjectMapper(), clock);
  }

  private ProcessingJobRepairExecutor executorAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new ProcessingJobRepairExecutor(
        requestRepository,
        processingJobRepository,
        new ProcessingJobStatusRepairValidator(),
        new AuditEventService(auditEventRepository, clock),
        new ObjectMapper(),
        clock);
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  /** A dry-run request of the given target, requested then approved by a separate actor at T0. */
  private UUID approvedRequest(DataRepairTargetType target) {
    DataRepairService svc = dataRepairServiceAt(T0);
    UUID requester = UUID.randomUUID();
    UUID requestId = svc.requestDryRun(tenantId, requester, target.name(), "stuck job").requestId();
    svc.requestApproval(tenantId, requester, requestId, "1 stuck processing job");
    svc.approve(tenantId, UUID.randomUUID(), requestId, "ok");
    return requestId;
  }

  private UUID dryRunOnlyRequest(DataRepairTargetType target) {
    return dataRepairServiceAt(T0).requestDryRun(tenantId, UUID.randomUUID(), target.name(), "stuck").requestId();
  }

  private ProcessingJob staleProcessingJob(UUID owningTenant) {
    ProcessingJob job = new ProcessingJob(owningTenant, "EXTRACTION", "DOCUMENT", UUID.randomUUID(), 0, T0);
    job.markProcessing(T0.minus(THRESHOLD.plusMinutes(5)));
    return processingJobRepository.save(job);
  }

  private ProcessingJobRepairException expectFailure(Runnable action) {
    return catchThrowableOfType(action::run, ProcessingJobRepairException.class);
  }

  // --- approval / target gate ---

  @Test
  void executionWithoutApprovalIsDeniedAndMutatesNothing() {
    UUID requestId = dryRunOnlyRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getCode()).isEqualTo(ProcessingJobRepairException.CODE_DENIED);
    assertThat(ex.getReasonCode()).isEqualTo("APPROVAL_MISSING");
    assertThat(countAudits("PROCESSING_JOB_REPAIR_EXECUTION_DENIED")).isEqualTo(1);
    assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void executionWithRejectedRequestIsDenied() {
    DataRepairService svc = dataRepairServiceAt(T0);
    UUID requester = UUID.randomUUID();
    UUID requestId =
        svc.requestDryRun(tenantId, requester, DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR.name(), "x")
            .requestId();
    svc.requestApproval(tenantId, requester, requestId, "s");
    svc.reject(tenantId, UUID.randomUUID(), requestId, "no");
    ProcessingJob job = staleProcessingJob(tenantId);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("APPROVAL_REJECTED");
  }

  @Test
  void executionWithExpiredApprovalIsDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);
    Instant afterExpiry = T0.plus(DataRepairService.APPROVAL_TTL).plus(Duration.ofMinutes(1));

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(afterExpiry)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("APPROVAL_EXPIRED");
    assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void approvedButWrongTargetTypeIsDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.ORDER_JOURNEY);
    ProcessingJob job = staleProcessingJob(tenantId);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("WRONG_TARGET_TYPE");
    assertThat(countAudits("PROCESSING_JOB_REPAIR_EXECUTION_DENIED")).isEqualTo(1);
  }

  @Test
  void crossTenantRequestIsNotFound() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);

    catchThrowableOfType(
        () -> executorAt(T0).execute(
            UUID.randomUUID(), UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"),
        NotFoundException.class);
  }

  // --- deterministic validation ---

  @Test
  void missingJobIsValidationDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, UUID.randomUUID(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getCode()).isEqualTo(ProcessingJobRepairException.CODE_VALIDATION_FAILED);
    assertThat(ex.getReasonCode()).isEqualTo("JOB_NOT_FOUND");
    assertThat(countAudits("PROCESSING_JOB_REPAIR_VALIDATION_FAILED")).isEqualTo(1);
  }

  @Test
  void crossTenantJobIsValidationDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob otherTenantJob = staleProcessingJob(UUID.randomUUID());

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, otherTenantJob.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("JOB_NOT_FOUND");
    assertThat(processingJobRepository.findById(otherTenantJob.getId()).orElseThrow().getStatus())
        .isEqualTo("PROCESSING");
  }

  @Test
  void expectedStatusMismatchIsValidationDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PENDING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("EXPECTED_STATUS_MISMATCH");
  }

  @Test
  void nonStaleRunningJobIsValidationDenied() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = new ProcessingJob(tenantId, "EXTRACTION", "DOCUMENT", UUID.randomUUID(), 0, T0);
    job.markProcessing(T0.minus(Duration.ofMinutes(1)));
    processingJobRepository.save(job);

    ProcessingJobRepairException ex = expectFailure(() -> executorAt(T0)
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix"));

    assertThat(ex.getReasonCode()).isEqualTo("JOB_NOT_STALE");
  }

  // --- allowlisted success ---

  @Test
  void approvedStaleProcessingJobIsRepairedAuditedAndStamped() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);
    UUID staff = UUID.randomUUID();

    ProcessingJobRepairResponse response = executorAt(T0)
        .execute(tenantId, staff, requestId, job.getId(), "PROCESSING", "FAILED", "stuck > 15m");

    assertThat(response.executionStatus()).isEqualTo(DataRepairRequest.ExecutionStatus.EXECUTED.name());
    assertThat(response.previousStatus()).isEqualTo("PROCESSING");
    assertThat(response.newStatus()).isEqualTo("FAILED");
    assertThat(response.targetId()).isEqualTo(job.getId());
    assertThat(response.executedAt()).isEqualTo(T0);

    // The one processing_job row was mutated to FAILED.
    assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
    // The request execution metadata was stamped.
    DataRepairRequest saved = requestRepository.findById(requestId).orElseThrow();
    assertThat(saved.getExecutionStatus()).isEqualTo(DataRepairRequest.ExecutionStatus.EXECUTED);
    assertThat(saved.getTargetProcessingJobId()).isEqualTo(job.getId());
    assertThat(saved.getExecutedBy()).isEqualTo(staff);
    // Full audit trail.
    assertThat(countAudits("PROCESSING_JOB_REPAIR_EXECUTION_STARTED")).isEqualTo(1);
    assertThat(countAudits("PROCESSING_JOB_REPAIR_EXECUTED")).isEqualTo(1);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_COMPLETED")).isEqualTo(1);
  }

  // --- idempotency / replay ---

  @Test
  void repeatedExecutionReplaysPriorResultWithoutDoubleMutation() {
    UUID requestId = approvedRequest(DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR);
    ProcessingJob job = staleProcessingJob(tenantId);
    executorAt(T0).execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "PROCESSING", "FAILED", "fix");

    // A second execute — note the job is now FAILED, so expectedCurrentStatus is no longer PROCESSING — yet
    // because the request is already EXECUTED the executor replays the prior result and never re-validates
    // or re-mutates.
    ProcessingJobRepairResponse replay = executorAt(T0.plus(Duration.ofMinutes(1)))
        .execute(tenantId, UUID.randomUUID(), requestId, job.getId(), "FAILED", "FAILED", "again");

    assertThat(replay.previousStatus()).isEqualTo("PROCESSING");
    assertThat(replay.newStatus()).isEqualTo("FAILED");
    assertThat(replay.message()).contains("replay");
    assertThat(countAudits("PROCESSING_JOB_REPAIR_EXECUTED")).isEqualTo(1);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_REPLAYED")).isEqualTo(1);
    assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
    // Exactly one job row exists — no duplicate mutation, no extra rows.
    assertThat(processingJobRepository.count()).isEqualTo(1);
  }
}
