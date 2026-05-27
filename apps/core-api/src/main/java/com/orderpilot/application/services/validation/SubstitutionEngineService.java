package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
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
  private final ProductSubstituteRepository substituteRepository; private final SubstituteCandidateRepository candidateRepository; private final InventorySnapshotRepository inventoryRepository; private final ProductCompatibilityRepository compatibilityRepository; private final CustomerSubstitutionPreferenceRepository preferenceRepository; private final ApprovalRequirementService approvalService; private final ValidationIssueService issueService; private final Clock clock;
  public SubstitutionEngineService(ProductSubstituteRepository substituteRepository, SubstituteCandidateRepository candidateRepository, InventorySnapshotRepository inventoryRepository, ProductCompatibilityRepository compatibilityRepository, CustomerSubstitutionPreferenceRepository preferenceRepository, ApprovalRequirementService approvalService, ValidationIssueService issueService, Clock clock) { this.substituteRepository=substituteRepository; this.candidateRepository=candidateRepository; this.inventoryRepository=inventoryRepository; this.compatibilityRepository=compatibilityRepository; this.preferenceRepository=preferenceRepository; this.approvalService=approvalService; this.issueService=issueService; this.clock=clock; }
  @Transactional
  public List<SubstituteCandidate> generate(UUID runId, UUID extractionResultId, UUID lineId, UUID sourceProductId, String triggerStatus) {
    return generate(runId, extractionResultId, lineId, sourceProductId, null, triggerStatus);
  }
  @Transactional
  public List<SubstituteCandidate> generate(UUID runId, UUID extractionResultId, UUID lineId, UUID sourceProductId, UUID customerAccountId, String triggerStatus) {
    if (sourceProductId == null || !("OUT_OF_STOCK".equals(triggerStatus) || "LOW_STOCK".equals(triggerStatus) || "INSUFFICIENT_STOCK".equals(triggerStatus) || "NOT_FOUND".equals(triggerStatus))) return List.of();
    UUID tenantId = TenantContext.requireTenantId();
    List<SubstituteCandidate> candidates = substituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, sourceProductId).stream()
        .sorted(Comparator.comparingInt((ProductSubstitute s) -> riskRank(s.getRiskLevel())).thenComparing(ProductSubstitute::getSubstituteProductId))
        .map(s -> create(tenantId, runId, lineId, s, customerAccountId, triggerStatus))
        .sorted(Comparator.comparing(SubstituteCandidate::getRankScore).reversed())
        .toList();
    if (candidates.isEmpty()) issueService.open(runId, extractionResultId, lineId, null, "SUBSTITUTE_REQUIRED", "WARNING", "Substitute may be required, but no deterministic substitute exists", "{}");
    return candidates;
  }
  @Transactional(readOnly = true) public List<SubstituteCandidate> list(UUID runId) { return candidateRepository.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(TenantContext.requireTenantId(), runId); }
  private SubstituteCandidate create(UUID tenantId, UUID runId, UUID lineId, ProductSubstitute substitute, UUID customerAccountId, String triggerStatus) {
    boolean highRisk = "HIGH".equalsIgnoreCase(substitute.getRiskLevel()) || substitute.isRequiresApproval();
    boolean blocked = customerAccountId != null && preferenceRepository.existsByTenantIdAndCustomerAccountIdAndProductIdAndBlockedSubstituteProductId(tenantId, customerAccountId, substitute.getSourceProductId(), substitute.getSubstituteProductId());
    boolean compatible = !compatibilityRepository.findByTenantIdAndProductIdAndActiveTrue(tenantId, substitute.getSubstituteProductId()).isEmpty();
    String inventoryStatus = inventoryStatus(tenantId, substitute.getSubstituteProductId());
    boolean stockAvailable = "AVAILABLE".equals(inventoryStatus);
    BigDecimal score = "LOW".equalsIgnoreCase(substitute.getRiskLevel()) ? new BigDecimal("70.0000") : "MEDIUM".equalsIgnoreCase(substitute.getRiskLevel()) ? new BigDecimal("50.0000") : new BigDecimal("25.0000");
    if (compatible) score = score.add(new BigDecimal("10.0000"));
    if (stockAvailable) score = score.add(new BigDecimal("15.0000"));
    if (blocked) score = BigDecimal.ZERO;
    issueService.open(runId, null, lineId, null, "SUBSTITUTE_AVAILABLE", "INFO", "Deterministic substitute candidate is available for operator review", "{\"substituteProductId\":\"" + substitute.getSubstituteProductId() + "\"}");
    boolean requiresApproval = highRisk || blocked;
    String status = blocked ? "BLOCKED_BY_CUSTOMER_POLICY" : highRisk ? "NEEDS_REVIEW" : "CANDIDATE";
    String reason = "Generated because source line status is " + triggerStatus + "; relation exists=true; compatibility=" + compatible + "; stock=" + inventoryStatus;
    SubstituteCandidate candidate = candidateRepository.save(new SubstituteCandidate(tenantId, runId, lineId, substitute.getSourceProductId(), substitute.getSubstituteProductId(), substitute.getSubstituteType(), substitute.getRiskLevel(), score, reason, inventoryStatus, highRisk ? "RISK_REVIEW" : "MARGIN_SAFE_BY_RULE", requiresApproval, status, clock.instant()));
    if (highRisk) approvalService.create(runId, lineId, "SUBSTITUTE_REQUIRES_APPROVAL", "HIGH", "High-risk substitute requires operator approval");
    if (blocked) approvalService.create(runId, lineId, "SUBSTITUTE_BLOCKED_BY_CUSTOMER_POLICY", "HIGH", "Blocked substitute cannot be treated as safe");
    return candidate;
  }
  private String inventoryStatus(UUID tenantId, UUID productId) {
    List<InventorySnapshot> snapshots = inventoryRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId);
    if (snapshots.isEmpty() || snapshots.get(0).getQuantityAvailable() == null) return "NO_SNAPSHOT";
    return snapshots.get(0).getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0 ? "AVAILABLE" : "OUT_OF_STOCK";
  }
  private int riskRank(String riskLevel) { return "LOW".equalsIgnoreCase(riskLevel) ? 0 : "MEDIUM".equalsIgnoreCase(riskLevel) ? 1 : 2; }
}
