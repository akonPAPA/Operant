package com.orderpilot.application.services.workspace;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.OperatorAction;
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorActionService {
  private final OperatorActionRepository repository; private final AuditEventService auditEventService; private final Clock clock;
  public OperatorActionService(OperatorActionRepository repository, AuditEventService auditEventService, Clock clock){this.repository=repository;this.auditEventService=auditEventService;this.clock=clock;}
  @Transactional
  public OperatorAction record(UUID actorUserId, String targetType, UUID targetId, String actionType, String message, String metadataJson) {
    OperatorAction action = repository.save(new OperatorAction(TenantContext.requireTenantId(), actorUserId, targetType, targetId, actionType, message, metadataJson == null ? "{}" : metadataJson, clock.instant()));
    auditEventService.record(actionType, targetType, targetId.toString(), actorUserId, metadataJson == null ? "{}" : metadataJson);
    return action;
  }
  @Transactional(readOnly = true) public List<OperatorAction> forTarget(String targetType, UUID targetId){return repository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), targetType, targetId);}
  @Transactional(readOnly = true) public List<OperatorAction> recent(){return repository.findTop25ByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
}
