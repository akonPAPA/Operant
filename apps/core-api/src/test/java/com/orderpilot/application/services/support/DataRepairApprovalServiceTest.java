package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairRequestResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
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
 * OP-CAP-52 — data-repair approval workflow + execution stub. Proves the approval gate (request → approve /
 * reject), that execution is impossible (denied without a valid approval, execution-disabled even with one),
 * that no business row is ever mutated, that an approver cannot approve their own request, and that every
 * approval/attempt is audited.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DataRepairApprovalServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");

  @Autowired private DataRepairRequestRepository requestRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;

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

  private DataRepairService serviceAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new DataRepairService(
        requestRepository, new AuditEventService(auditEventRepository, clock), new ObjectMapper(), clock);
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  private UUID dryRunRequest(UUID requester) {
    DataRepairDryRunResponse dryRun =
        serviceAt(T0).requestDryRun(tenantId, requester, "ORDER_JOURNEY", "stuck milestone");
    return dryRun.requestId();
  }

  @Test
  void dryRunStillWorksAndLeavesApprovalNone() {
    UUID requestId = dryRunRequest(UUID.randomUUID());

    DataRepairRequest saved = requestRepository.findById(requestId).orElseThrow();
    assertThat(saved.getApprovalStatus()).isEqualTo(DataRepairRequest.ApprovalStatus.NONE);
    assertThat(saved.getExecutionStatus()).isEqualTo(DataRepairRequest.ExecutionStatus.EXECUTION_DISABLED);
  }

  @Test
  void requestApprovalMovesToPendingWithExpiryAndAudits() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);

    DataRepairRequestResponse response = serviceAt(T0)
        .requestApproval(tenantId, requester, requestId, "1 stuck order journey milestone");

    assertThat(response.approvalStatus()).isEqualTo(DataRepairRequest.ApprovalStatus.PENDING_APPROVAL.name());
    assertThat(response.affectedTargetSummary()).isEqualTo("1 stuck order journey milestone");
    assertThat(response.approvalExpiresAt()).isEqualTo(T0.plus(DataRepairService.APPROVAL_TTL));
    assertThat(countAudits("DATA_REPAIR_APPROVAL_REQUESTED")).isEqualTo(1);
  }

  @Test
  void executionWithoutApprovalIsDeniedAndAuditedAndMutatesNothing() {
    UUID requestId = dryRunRequest(UUID.randomUUID());

    DataRepairExecutionException ex = catchThrowableOfType(
        () -> serviceAt(T0).attemptExecution(tenantId, UUID.randomUUID(), requestId),
        DataRepairExecutionException.class);

    assertThat(ex.getCode()).isEqualTo(DataRepairExecutionException.CODE_DENIED);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_ATTEMPT_DENIED")).isEqualTo(1);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_DISABLED")).isZero();
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void executionWithRejectedApprovalIsDenied() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);
    serviceAt(T0).requestApproval(tenantId, requester, requestId, "summary");
    serviceAt(T0).reject(tenantId, UUID.randomUUID(), requestId, "no");

    DataRepairExecutionException ex = catchThrowableOfType(
        () -> serviceAt(T0).attemptExecution(tenantId, UUID.randomUUID(), requestId),
        DataRepairExecutionException.class);

    assertThat(ex.getCode()).isEqualTo(DataRepairExecutionException.CODE_DENIED);
    assertThat(countAudits("DATA_REPAIR_REJECTED")).isEqualTo(1);
  }

  @Test
  void executionWithApprovedRequestReturnsExecutionDisabledAndMutatesNothing() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);
    serviceAt(T0).requestApproval(tenantId, requester, requestId, "summary");
    serviceAt(T0).approve(tenantId, UUID.randomUUID(), requestId, "ok");

    DataRepairExecutionException ex = catchThrowableOfType(
        () -> serviceAt(T0).attemptExecution(tenantId, UUID.randomUUID(), requestId),
        DataRepairExecutionException.class);

    assertThat(ex.getCode()).isEqualTo(DataRepairExecutionException.CODE_DISABLED);
    assertThat(ex.getHttpStatus()).isEqualTo(501);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_DISABLED")).isEqualTo(1);
    // Approved-but-disabled execution mutates no business row and the execution status never changes.
    assertThat(processingJobRepository.count()).isZero();
    assertThat(requestRepository.findById(requestId).orElseThrow().getExecutionStatus())
        .isEqualTo(DataRepairRequest.ExecutionStatus.EXECUTION_DISABLED);
  }

  @Test
  void executionWithExpiredApprovalIsDenied() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);
    serviceAt(T0).requestApproval(tenantId, requester, requestId, "summary");
    serviceAt(T0).approve(tenantId, UUID.randomUUID(), requestId, "ok");

    // Attempt after the approval has expired.
    Instant afterExpiry = T0.plus(DataRepairService.APPROVAL_TTL).plus(Duration.ofMinutes(1));
    DataRepairExecutionException ex = catchThrowableOfType(
        () -> serviceAt(afterExpiry).attemptExecution(tenantId, UUID.randomUUID(), requestId),
        DataRepairExecutionException.class);

    assertThat(ex.getCode()).isEqualTo(DataRepairExecutionException.CODE_DENIED);
    assertThat(countAudits("DATA_REPAIR_EXECUTION_ATTEMPT_DENIED")).isEqualTo(1);
  }

  @Test
  void approverCannotApproveTheirOwnRequest() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);
    serviceAt(T0).requestApproval(tenantId, requester, requestId, "summary");

    assertThatThrownBy(() -> serviceAt(T0).approve(tenantId, requester, requestId, "self"))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(requestRepository.findById(requestId).orElseThrow().getApprovalStatus())
        .isEqualTo(DataRepairRequest.ApprovalStatus.PENDING_APPROVAL);
    assertThat(countAudits("DATA_REPAIR_APPROVAL_DENIED")).isEqualTo(1);
  }

  @Test
  void requestingApprovalTwiceIsAConflict() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);
    serviceAt(T0).requestApproval(tenantId, requester, requestId, "summary");

    assertThatThrownBy(() -> serviceAt(T0).requestApproval(tenantId, requester, requestId, "again"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void approveAndAttemptForRequestFromAnotherTenantIsNotFound() {
    UUID requester = UUID.randomUUID();
    UUID requestId = dryRunRequest(requester);

    assertThatThrownBy(() -> serviceAt(T0).requestApproval(UUID.randomUUID(), requester, requestId, "x"))
        .isInstanceOf(com.orderpilot.common.errors.NotFoundException.class);
    assertThatThrownBy(() -> serviceAt(T0).attemptExecution(UUID.randomUUID(), requester, requestId))
        .isInstanceOf(com.orderpilot.common.errors.NotFoundException.class);
  }
}
