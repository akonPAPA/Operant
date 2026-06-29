package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.workspace.QuoteHandoffSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteExternalWritePreparationService {
  private final QuoteHandoffReadinessService readinessService;
  private final QuoteHandoffSnapshotService snapshotService;
  private final ChangeRequestService changeRequestService;
  private final ChangeRequestRepository changeRequestRepository;
  private final ConnectorCommandRepository connectorCommandRepository;

  public QuoteExternalWritePreparationService(QuoteHandoffReadinessService readinessService, QuoteHandoffSnapshotService snapshotService, ChangeRequestService changeRequestService, ChangeRequestRepository changeRequestRepository, ConnectorCommandRepository connectorCommandRepository) {
    this.readinessService = readinessService;
    this.snapshotService = snapshotService;
    this.changeRequestService = changeRequestService;
    this.changeRequestRepository = changeRequestRepository;
    this.connectorCommandRepository = connectorCommandRepository;
  }

  @Transactional(readOnly = true)
  public QuoteHandoffResponse checkReadiness(UUID quoteId, QuoteHandoffCommand command) {
    return readinessService.check(quoteId, command == null ? null : command.actorId());
  }

  @Transactional
  public QuoteHandoffResponse prepareSnapshot(UUID quoteId, QuoteHandoffCommand command) {
    QuoteHandoffSnapshot snapshot = snapshotService.prepare(quoteId, command);
    var quote = readinessService.getQuote(TenantContext.requireTenantId(), quoteId);
    return new QuoteHandoffResponse(quoteId, quote.getStatus(), "HANDOFF_PREPARED", List.of(), snapshot != null, null, false, List.of("CREATE_CHANGE_REQUEST_DRAFT"));
  }

  @Transactional
  public QuoteHandoffResponse createChangeRequestDraft(UUID quoteId, ChangeRequestDraftCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    var quote = readinessService.getQuote(tenantId, quoteId);
    QuoteHandoffSnapshot snapshot = snapshotService.prepare(quoteId, new QuoteHandoffCommand(command == null ? null : command.actorId(), null, "Create Stage 11E ChangeRequest draft"));
    ChangeRequest request = changeRequestRepository.findByTenantIdAndPayloadSnapshotId(tenantId, snapshot.getId())
        .orElseGet(() -> changeRequestService.createStage11EDraft(
            valueOr(command == null ? null : command.targetSystemType(), "DEMO_ERP"),
            valueOr(command == null ? null : command.targetEntityType(), "DRAFT_QUOTE"),
            valueOr(command == null ? null : command.requestedAction(), "CREATE_DRAFT_QUOTE"),
            "QUOTE",
            quoteId,
            snapshot.getId(),
            snapshot.getPayloadJson(),
            "change-request:" + snapshot.getIdempotencyKey(),
            snapshot.getPayloadHash(),
            command == null ? null : command.actorId()));
    if (connectorCommandRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().anyMatch(cmd -> cmd.getChangeRequestId().equals(request.getId()))) {
      changeRequestService.blockStage11E(request.getId(), "Stage 11E must not create connector commands");
      throw new QuoteHandoffViolation("Stage 11E must not create connector commands");
    }
    return new QuoteHandoffResponse(quoteId, quote.getStatus(), "HANDOFF_PREPARED", List.of(), snapshot != null, request.getId(), false, List.of("APPROVE_INTERNAL", "CANCEL"));
  }

  @Transactional
  public QuoteHandoffResponse approveInternal(UUID changeRequestId, UUID actorId) {
    ChangeRequest request = changeRequestService.approveInternalStage11E(changeRequestId, actorId);
    return response(request, List.of("CANCEL"));
  }

  @Transactional
  public QuoteHandoffResponse cancel(UUID changeRequestId, ChangeRequestCancelCommand command) {
    ChangeRequest request = changeRequestService.cancelStage11E(changeRequestId, command == null ? null : command.actorId(), command == null ? null : command.reason());
    return response(request, List.of());
  }

  private QuoteHandoffResponse response(ChangeRequest request, List<String> allowedActions) {
    return new QuoteHandoffResponse(request.getSourceId(), "APPROVED", request.getApprovalStatus(), List.of(), request.getPayloadSnapshotId() != null, request.getId(), false, allowedActions);
  }

  private static String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
