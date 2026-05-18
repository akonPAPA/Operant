package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalRequirementService {
  private final ApprovalRequirementRepository repository;
  private final Clock clock;

  public ApprovalRequirementService(ApprovalRequirementRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public ApprovalRequirement create(UUID validationRunId, UUID lineItemId, String type, String severity, String reason) {
    return repository.save(new ApprovalRequirement(TenantContext.requireTenantId(), validationRunId, lineItemId, type, severity, reason, clock.instant()));
  }

  @Transactional(readOnly = true)
  public List<ApprovalRequirement> list(UUID validationRunId) {
    return repository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), validationRunId);
  }

  @Transactional
  public ApprovalRequirement approve(UUID id) {
    ApprovalRequirement requirement = repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
    requirement.setStatus("APPROVED", clock.instant());
    return repository.save(requirement);
  }

  @Transactional
  public ApprovalRequirement reject(UUID id) {
    ApprovalRequirement requirement = repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
    requirement.setStatus("REJECTED", clock.instant());
    return repository.save(requirement);
  }
}
