package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.validation.SubstituteCandidate;
import com.orderpilot.domain.validation.SubstituteCandidateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubstitutionEngineService {
  private final ProductSubstituteRepository substituteRepository; private final SubstituteCandidateRepository candidateRepository; private final ApprovalRequirementService approvalService; private final ValidationIssueService issueService; private final Clock clock;
  public SubstitutionEngineService(ProductSubstituteRepository substituteRepository, SubstituteCandidateRepository candidateRepository, ApprovalRequirementService approvalService, ValidationIssueService issueService, Clock clock) { this.substituteRepository=substituteRepository; this.candidateRepository=candidateRepository; this.approvalService=approvalService; this.issueService=issueService; this.clock=clock; }
  @Transactional
  public List<SubstituteCandidate> generate(UUID runId, UUID extractionResultId, UUID lineId, UUID sourceProductId, String triggerStatus) {
    if (sourceProductId == null || !("OUT_OF_STOCK".equals(triggerStatus) || "INSUFFICIENT_STOCK".equals(triggerStatus) || "NOT_FOUND".equals(triggerStatus))) return List.of();
    UUID tenantId = TenantContext.requireTenantId();
    List<SubstituteCandidate> candidates = substituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, sourceProductId).stream()
        .sorted(Comparator.comparingInt(s -> riskRank(s.getRiskLevel())))
        .map(s -> create(tenantId, runId, lineId, s, triggerStatus))
        .toList();
    if (candidates.isEmpty()) issueService.open(runId, extractionResultId, lineId, null, "SUBSTITUTE_REQUIRED", "WARNING", "Substitute may be required, but no deterministic substitute exists", "{}");
    return candidates;
  }
  @Transactional(readOnly = true) public List<SubstituteCandidate> list(UUID runId) { return candidateRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(TenantContext.requireTenantId(), runId); }
  private SubstituteCandidate create(UUID tenantId, UUID runId, UUID lineId, ProductSubstitute substitute, String triggerStatus) {
    boolean highRisk = "HIGH".equalsIgnoreCase(substitute.getRiskLevel()) || substitute.isRequiresApproval();
    BigDecimal score = "LOW".equalsIgnoreCase(substitute.getRiskLevel()) ? new BigDecimal("90.0000") : "MEDIUM".equalsIgnoreCase(substitute.getRiskLevel()) ? new BigDecimal("70.0000") : new BigDecimal("40.0000");
    SubstituteCandidate candidate = candidateRepository.save(new SubstituteCandidate(tenantId, runId, lineId, substitute.getSourceProductId(), substitute.getSubstituteProductId(), substitute.getSubstituteType(), substitute.getRiskLevel(), score, "Generated because source line status is " + triggerStatus, null, null, highRisk, highRisk ? "NEEDS_REVIEW" : "CANDIDATE", clock.instant()));
    if (highRisk) approvalService.create(runId, lineId, "HIGH_RISK_SUBSTITUTE", "HIGH", "High-risk substitute requires operator approval");
    return candidate;
  }
  private int riskRank(String riskLevel) { return "LOW".equalsIgnoreCase(riskLevel) ? 0 : "MEDIUM".equalsIgnoreCase(riskLevel) ? 1 : 2; }
}
