package com.orderpilot.application.services.workspace;

import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuggestedFixService {
  private final SuggestedFixRepository repository; private final ValidationIssueRepository issueRepository; private final SubstituteCandidateRepository substituteRepository; private final ExceptionCaseRepository caseRepository; private final OperatorActionService actionService; private final JsonSupport jsonSupport; private final Clock clock;
  public SuggestedFixService(SuggestedFixRepository repository, ValidationIssueRepository issueRepository, SubstituteCandidateRepository substituteRepository, ExceptionCaseRepository caseRepository, OperatorActionService actionService, JsonSupport jsonSupport, Clock clock){this.repository=repository;this.issueRepository=issueRepository;this.substituteRepository=substituteRepository;this.caseRepository=caseRepository;this.actionService=actionService;this.jsonSupport=jsonSupport;this.clock=clock;}
  @Transactional
  public List<SuggestedFix> generate(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID caseId = caseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().filter(c -> validationRunId.equals(c.getValidationRunId())).map(ExceptionCase::getId).findFirst().orElse(null);
    List<SuggestedFix> issueFixes = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream().filter(i -> "OPEN".equals(i.getStatus())).map(i -> repository.save(new SuggestedFix(tenantId, caseId, validationRunId, i.getId(), i.getExtractedLineItemId(), fixType(i), jsonSupport.writeObject(Map.of("issueType", i.getIssueType(), "message", i.getMessage())), new BigDecimal("0.7000"), reason(i), clock.instant()))).toList();
    substituteRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, validationRunId).forEach(s -> repository.save(new SuggestedFix(tenantId, caseId, validationRunId, null, s.getExtractedLineItemId(), "SUBSTITUTE_SELECT", jsonSupport.writeObject(Map.of("substituteProductId", s.getSubstituteProductId(), "riskLevel", s.getRiskLevel(), "status", s.getStatus())), s.getRankScore().divide(new BigDecimal("100")), "Select deterministic substitute candidate", clock.instant())));
    actionService.record(null, "VALIDATION_RUN", validationRunId, "OTHER", "Suggested fixes generated", "{}");
    return repository.findByTenantIdAndValidationRunId(tenantId, validationRunId);
  }
  @Transactional(readOnly = true) public List<SuggestedFix> list(UUID validationRunId){return repository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId);}
  @Transactional(readOnly = true) public SuggestedFix get(UUID id){return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();}
  @Transactional public SuggestedFix accept(UUID id){SuggestedFix f=get(id); f.setStatus("ACCEPTED", clock.instant()); actionService.record(null, "SUGGESTED_FIX", id, "FIX_ACCEPTED", "Suggested fix accepted as workflow state", "{}"); return repository.save(f);}
  @Transactional public SuggestedFix reject(UUID id){SuggestedFix f=get(id); f.setStatus("REJECTED", clock.instant()); actionService.record(null, "SUGGESTED_FIX", id, "FIX_REJECTED", "Suggested fix rejected", "{}"); return repository.save(f);}
  private String fixType(ValidationIssue i){return switch(i.getIssueType()){case "CUSTOMER_NOT_FOUND","CUSTOMER_AMBIGUOUS" -> "CONFIRM_CUSTOMER_MATCH"; case "PRODUCT_NOT_FOUND","PRODUCT_AMBIGUOUS","PRODUCT_ALIAS_MATCHED","OEM_MATCHED" -> "SELECT_PRODUCT_CANDIDATE"; case "INVALID_UOM","UOM_NORMALIZED" -> "NORMALIZE_UOM"; case "INVALID_QUANTITY" -> "ADJUST_QUANTITY"; case "OUT_OF_STOCK","LOW_STOCK","SUBSTITUTE_AVAILABLE","SUBSTITUTE_REQUIRES_APPROVAL" -> "SELECT_SUBSTITUTE_CANDIDATE"; case "DISCOUNT_REQUIRES_APPROVAL","MARGIN_BELOW_GUARDRAIL","LOW_EXTRACTION_CONFIDENCE" -> "REQUEST_MANAGER_APPROVAL"; case "REQUESTED_DATE_INVALID" -> "NEEDS_MANUAL_FOLLOW_UP"; default -> "ESCALATE_TO_SUPERVISOR";};}
  private String reason(ValidationIssue i){return "Workflow suggestion for validation issue " + i.getIssueType();}
}
