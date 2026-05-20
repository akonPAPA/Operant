package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChangeRequestService.class, AuditEventService.class, CoreConfiguration.class})
class ChangeRequestServiceTest {
  @Autowired private ChangeRequestService service;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void creatingChangeRequestPersistsTenantScopedRecordAndOutboxEvent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    ChangeRequest request = create("{}");

    assertThat(request.getTenantId()).isEqualTo(tenantId);
    assertThat(request.getValidationStatus()).isEqualTo("PENDING_VALIDATION");
    assertThat(request.getApprovalStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(request.getExecutionStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(changeRequestRepository.findByIdAndTenantId(request.getId(), tenantId)).isPresent();
    assertThat(outboxEventRepository.findByTenantIdAndAggregateTypeAndAggregateIdOrderByCreatedAtDesc(tenantId, "CHANGE_REQUEST", request.getId()))
        .extracting("eventType")
        .contains("CHANGE_REQUEST_CREATED");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANGE_REQUEST_CREATED");
  }

  @Test
  void duplicateIdempotencyKeyReturnsExistingWriteIntent() {
    TenantContext.setTenantId(UUID.randomUUID());

    ChangeRequest first = service.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{\"total\":100}", "stage10c-demo-key", null);
    ChangeRequest second = service.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{\"total\":200}", "stage10c-demo-key", null);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(changeRequestRepository.findAll()).hasSize(1);
    assertThat(outboxEventRepository.findAll()).hasSize(1);
  }

  @Test
  void validationMarksRequestValidatedForJsonObjectPayload() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = create("{\"draftQuoteId\":\"demo\"}");

    ChangeRequest validated = service.validateChangeRequest(request.getId());

    assertThat(validated.getValidationStatus()).isEqualTo("VALIDATED");
    assertThat(validated.getValidatedAt()).isNotNull();
    assertThat(outboxEventRepository.findAll()).extracting("eventType").contains("CHANGE_REQUEST_VALIDATED");
  }

  @Test
  void validationFailureMarksRequestValidationFailedForNonObjectPayload() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = create("[]");

    ChangeRequest failed = service.validateChangeRequest(request.getId());

    assertThat(failed.getValidationStatus()).isEqualTo("VALIDATION_FAILED");
    assertThat(failed.getFailureReason()).contains("JSON object");
    assertThat(outboxEventRepository.findAll()).extracting("eventType").contains("CHANGE_REQUEST_VALIDATION_FAILED");
  }

  @Test
  void approvalMarksApprovedButKeepsExternalExecutionDisabled() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = create("{\"draftOrderId\":\"demo\"}");
    UUID approver = UUID.randomUUID();

    ChangeRequest approved = service.approveChangeRequest(request.getId(), approver);

    assertThat(approved.getApprovalStatus()).isEqualTo("APPROVED");
    assertThat(approved.getApprovedByUserId()).isEqualTo(approver);
    assertThat(approved.getExecutionStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(approved.getExecutedAt()).isNull();
    assertThat(approved.getExternalReference()).isNull();
    assertThat(outboxEventRepository.findAll()).extracting("eventType").contains("CHANGE_REQUEST_APPROVED", "CHANGE_REQUEST_EXTERNAL_EXECUTION_DISABLED");
  }

  @Test
  void stage11eDraftCanBeApprovedInternalButRemainsExecutionDisabled() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID snapshotId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    ChangeRequest request = service.createStage11EDraft("DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "QUOTE", quoteId, snapshotId, "{\"quoteId\":\"" + quoteId + "\"}", "change-request:stage11e", "abc123", UUID.randomUUID());

    ChangeRequest approved = service.approveInternalStage11E(request.getId(), UUID.randomUUID());

    assertThat(approved.getSourceType()).isEqualTo("QUOTE");
    assertThat(approved.getPayloadSnapshotId()).isEqualTo(snapshotId);
    assertThat(approved.getPayloadHash()).isEqualTo("abc123");
    assertThat(approved.getValidationStatus()).isEqualTo("VALID");
    assertThat(approved.getApprovalStatus()).isEqualTo("APPROVED_INTERNAL");
    assertThat(approved.getExecutionStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(approved.getExecutedAt()).isNull();
    assertThat(approved.getExternalReference()).isNull();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANGE_REQUEST_DRAFT_CREATED", "CHANGE_REQUEST_APPROVED_INTERNAL", "CHANGE_REQUEST_EXECUTION_BLOCKED_STAGE_11E");
  }

  @Test
  void stage11eDraftCanBeCancelledBeforeAnyExternalExecution() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = service.createStage11EDraft("DEMO_ERP", "DRAFT_ORDER", "CREATE_DRAFT_ORDER", "QUOTE", UUID.randomUUID(), UUID.randomUUID(), "{}", "change-request:cancel", "hash", null);

    ChangeRequest cancelled = service.cancelStage11E(request.getId(), UUID.randomUUID(), "Operator stopped handoff");

    assertThat(cancelled.getApprovalStatus()).isEqualTo("CANCELLED");
    assertThat(cancelled.getExecutionStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(cancelled.getCancellationReason()).contains("Operator stopped");
    assertThat(cancelled.getExecutedAt()).isNull();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANGE_REQUEST_CANCELLED");
  }

  @Test
  void rejectionMarksRejectedWithReasonAndNotExecutable() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = create("{\"draftOrderId\":\"demo\"}");

    ChangeRequest rejected = service.rejectChangeRequest(request.getId(), "Operator declined write intent");

    assertThat(rejected.getApprovalStatus()).isEqualTo("REJECTED");
    assertThat(rejected.getExecutionStatus()).isEqualTo("NOT_EXECUTABLE");
    assertThat(rejected.getFailureReason()).contains("Operator declined");
    assertThat(rejected.getRejectedAt()).isNotNull();
  }

  @Test
  void tenantIsolationRespectsCurrentTenantContext() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChangeRequest requestA = create("{\"tenant\":\"a\"}");
    TenantContext.setTenantId(tenantB);
    ChangeRequest requestB = create("{\"tenant\":\"b\"}");

    assertThat(service.listChangeRequests()).extracting("id").containsExactly(requestB.getId());
    assertThat(changeRequestRepository.findByIdAndTenantId(requestA.getId(), tenantB)).isEmpty();
  }

  @Test
  void outboxEventsRemainInternalOnlyAndDoNotRepresentConnectorDispatch() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = create("{\"safe\":true}");
    service.validateChangeRequest(request.getId());
    service.approveChangeRequest(request.getId(), null);

    assertThat(service.listOutboxEvents()).allSatisfy(event -> {
      assertThat(event.getAggregateType()).isEqualTo("CHANGE_REQUEST");
      assertThat(event.getStatus()).isEqualTo("PENDING");
      assertThat(event.getEventType()).doesNotContain("CONNECTOR_DISPATCHED");
    });
  }

  private ChangeRequest create(String payloadJson) {
    return service.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), payloadJson, UUID.randomUUID().toString(), null);
  }
}
