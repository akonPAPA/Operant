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
  private String fixType(ValidationIssue i){return switch(i.getIssueType()){case "CUSTOMER_NOT_FOUND","CUSTOMER_AMBIGUOUS" -> "CUSTOMER_SELECT"; case "PRODUCT_NOT_FOUND","PRODUCT_AMBIGUOUS","SKU_ALIAS_MATCHED","OEM_MATCHED" -> "PRODUCT_SELECT"; case "UOM_UNKNOWN","UOM_NORMALIZED" -> "UOM_NORMALIZE"; case "OUT_OF_STOCK","INSUFFICIENT_STOCK","SUBSTITUTE_REQUIRED","SUBSTITUTE_HIGH_RISK" -> "SUBSTITUTE_SELECT"; case "PRICE_NOT_FOUND" -> "PRICE_SELECT"; case "DISCOUNT_EXCEEDS_RULE" -> "DISCOUNT_ADJUST"; case "MARGIN_BELOW_THRESHOLD" -> "MARGIN_APPROVAL"; default -> "MANUAL_EDIT";};}
  private String reason(ValidationIssue i){return "Workflow suggestion for validation issue " + i.getIssueType();}
}
