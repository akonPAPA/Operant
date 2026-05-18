package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationIssueService {
  private final ValidationIssueRepository repository;
  private final Clock clock;

  public ValidationIssueService(ValidationIssueRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public ValidationIssue open(UUID validationRunId, UUID extractionResultId, UUID lineItemId, UUID fieldId, String type, String severity, String message, String detailsJson) {
    return repository.save(new ValidationIssue(TenantContext.requireTenantId(), validationRunId, extractionResultId, lineItemId, fieldId, type, severity, message, detailsJson, clock.instant()));
  }

  @Transactional(readOnly = true)
  public List<ValidationIssue> list(UUID validationRunId) {
    return repository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), validationRunId);
  }

  @Transactional
  public ValidationIssue resolve(UUID id) {
    ValidationIssue issue = repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
    issue.setStatus("RESOLVED", clock.instant());
    return repository.save(issue);
  }

  @Transactional
  public ValidationIssue waive(UUID id) {
    ValidationIssue issue = repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
    issue.setStatus("WAIVED", clock.instant());
    return repository.save(issue);
  }
}
